package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.entity.User;
import uno.acloud.user.infrastructure.mq.UserEventPublisher;
import uno.acloud.user.mapper.UserMapper;

/**
 * 用户管理后台服务。
 * <p>处理用户注销/删除及跨服务数据清理事件发布。</p>
 */
@Slf4j
@Service
public class UserAdminService {

    private final UserMapper userMapper;
    private final UserEventPublisher userEventPublisher;

    public UserAdminService(UserMapper userMapper, UserEventPublisher userEventPublisher) {
        this.userMapper = userMapper;
        this.userEventPublisher = userEventPublisher;
    }

    /**
     * 注销用户：删除用户记录，事务提交后发布 user.deleted 事件。
     *
     * <p>采用两阶段模式：
     * <ol>
     *   <li>DB 事务内删除用户记录</li>
     *   <li>通过 TransactionSynchronizationManager 在事务提交后发布 MQ 事件</li>
     * </ol>
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND, "用户不存在");
        }

        String username = user.getUsername();
        int deleted = userMapper.deleteById(userId);
        if (deleted != 1) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户删除失败");
        }
        log.info("用户记录已删除: userId={}, username={}", userId, username);

        // 事务提交后发布 MQ 事件，避免在事务内进行远程 I/O
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userEventPublisher.publishUserDeleted(userId, username);
                } catch (Exception e) {
                    log.error("发布用户删除事件失败（事务已提交，不可回滚）: userId={}", userId, e);
                }
            }
        });
    }
}
