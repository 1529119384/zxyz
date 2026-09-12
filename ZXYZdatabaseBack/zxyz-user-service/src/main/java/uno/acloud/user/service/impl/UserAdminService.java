package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.client.TeamServicePermissionClient;
import uno.acloud.user.infrastructure.mq.UserEventPublisher;
import uno.acloud.user.mapper.UserMapper;

import java.util.List;

/**
 * 用户管理后台服务。
 * <p>处理用户注销/删除及跨服务数据清理事件发布。</p>
 */
@Slf4j
@Service
public class UserAdminService {

    private final UserMapper userMapper;
    private final UserEventPublisher userEventPublisher;
    private final TeamServicePermissionClient teamServicePermissionClient;
    /** 写事务模板：把删除动作收窄到最小事务范围（审计 L12）。 */
    private final TransactionTemplate deleteTransaction;

    public UserAdminService(UserMapper userMapper,
                            UserEventPublisher userEventPublisher,
                            TeamServicePermissionClient teamServicePermissionClient,
                            PlatformTransactionManager transactionManager) {
        this.userMapper = userMapper;
        this.userEventPublisher = userEventPublisher;
        this.teamServicePermissionClient = teamServicePermissionClient;
        this.deleteTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 注销用户：校验保护条件后删除用户记录，事务提交后发布 user.deleted 事件。
     *
     * <p>审计 L12：原先整个方法都在 {@code @Transactional} 内，而前两步保护性校验是远程只读 HTTP
     * （查系统角色、列管理员）——team-service 一抖动，事务就会长时间占着 DB 连接干等，
     * 与本仓库「HTTP 调用勿放事务内」的约定相悖。现在校验在事务外完成，
     * 写事务用 {@link TransactionTemplate} 收窄到「删除 + 注册 afterCommit」。</p>
     */
    public void deleteUser(Long operatorId, Long targetUserId) {
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "管理员不能注销自身账户");
        }

        User targetUser = userMapper.getById(targetUserId);
        if (targetUser == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND, "用户不存在");
        }

        List<String> targetRoles = teamServicePermissionClient.getSystemRolesByUserId(targetUserId);
        if (targetRoles.contains(SystemRoleCodes.SYSTEM_ADMIN)) {
            List<Long> adminIds = teamServicePermissionClient.listSystemAdminUserIds();
            if (adminIds == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法验证系统管理员数量，请稍后重试");
            }
            if (adminIds.size() == 1 && adminIds.get(0).equals(targetUserId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "不能删除最后一位系统管理员，请先分配其他管理员");
            }
        }

        String username = targetUser.getUsername();
        deleteTransaction.executeWithoutResult(status -> {
            int deleted = userMapper.deleteById(targetUserId);
            if (deleted != 1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户删除失败");
            }
            log.info("用户记录已删除: userId={}, username={}, operatorId={}", targetUserId, username, operatorId);

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        userEventPublisher.publishUserDeleted(targetUserId, username);
                    } catch (Exception e) {
                        log.error("发布用户删除事件失败（事务已提交，不可回滚）: userId={}", targetUserId, e);
                    }
                }
            });
        });
    }
}
