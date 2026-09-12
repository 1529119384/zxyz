package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.util.BatchPermissionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.PermissionCache;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.PermissionRoleMapper;

import java.util.List;
import java.util.Set;

import static uno.acloud.common.InputNormalizer.requireText;

@Slf4j
/**
 * 负责系统级用户角色分配、默认角色初始化
 */
@Service
public class UserRoleBindingService {

    private final PermissionRoleMapper permissionRoleMapper;
    private final UserServiceClient userServiceClient;
    private final AuditLogService auditLogService;
    private final StringRedisTemplate stringRedisTemplate;

    public UserRoleBindingService(PermissionRoleMapper permissionRoleMapper,
                                  UserServiceClient userServiceClient,
                                  AuditLogService auditLogService,
                                  StringRedisTemplate stringRedisTemplate) {
        this.permissionRoleMapper = permissionRoleMapper;
        this.userServiceClient = userServiceClient;
        this.auditLogService = auditLogService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 获取用户系统角色 code 列表 */
    public List<String> getSystemRolesByUserId(Long userId) {
        return permissionRoleMapper.getRoleByUserID(userId);
    }

    /** 分配系统角色给用户 */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoleToUser(Long userId, String roleCode, Long operatorId, String ipAddress) {
        if (userId == null || userId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 非法");
        }
        RoleEntity role = permissionRoleMapper.getRoleByCode(requireText(roleCode, "roleCode 不能为空"));
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        List<String> beforeCodes = getSystemRolesByUserId(userId);
        permissionRoleMapper.deleteUserRoles(userId);
        permissionRoleMapper.insertUserRole(userId, role.getId());
        auditLogService.writeSystemAudit(operatorId, "system", "user:assign-role", "user", Long.valueOf(userId),
                String.join(",", beforeCodes), role.getRoleCode(), ipAddress);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userServiceClient.clearPermissionCache(userId);
                } catch (Exception e) {
                    log.warn("Failed to clear permission cache for user {} after commit", userId, e);
                }
                publishPermissionInvalidation(userId);
            }
        });
    }

    /**
     * 为新用户分配默认角色（<b>固定为普通用户 SYSTEM_USER</b>）。
     *
     * <p>历史实现是「系统里还没有 SYSTEM_ADMIN 时，把新用户提升为管理员」。
     * 这条启发式规则必须删除（审计 2.1.1）：注册与登录都是公网可达路径，
     * 在空环境（新部署 / 数据重建 / 角色绑定表被清空）里任何先到者都会被自动提权成
     * 系统管理员；count 与 INSERT 之间还存在并发竞态，两人同时注册可各自拿到管理员。
     * 管理员引导的唯一入口是 user-service 的 {@code AdminBootstrapRunner}
     * （受部署变量 ADMIN_INIT_USERNAME / ADMIN_INIT_PASSWORD / app.admin.bootstrap.enabled 控制），
     * 它走 assignBootstrapAdminRole，不依赖这条启发式。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultRole(Long userId, String username) {
        if (userId == null || permissionRoleMapper.countUserRoles(userId) > 0) {
            return;
        }
        assignRoleByCode(userId, SystemRoleCodes.SYSTEM_USER);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userServiceClient.clearPermissionCache(userId);
                } catch (Exception e) {
                    log.warn("Failed to clear permission cache for user {} after commit", userId, e);
                }
                publishPermissionInvalidation(userId);
            }
        });
    }

    /** 引导阶段分配管理员角色 */
    @Transactional(rollbackFor = Exception.class)
    public void assignBootstrapAdminRole(Long userId) {
        if (userId == null || permissionRoleMapper.countUserRoles(userId) > 0) {
            return;
        }
        assignRoleByCode(userId, SystemRoleCodes.SYSTEM_ADMIN);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    userServiceClient.clearPermissionCache(userId);
                } catch (Exception e) {
                    log.warn("Failed to clear permission cache for user {} after commit", userId, e);
                }
                publishPermissionInvalidation(userId);
            }
        });
    }

    // ==================== 私有方法 ====================

    private void assignRoleByCode(Long userId, String roleCode) {
        boolean isNewRole = false;
        RoleEntity role = permissionRoleMapper.getRoleByCode(roleCode);
        if (role == null) {
            role = new RoleEntity();
            role.setRoleCode(roleCode);
            role.setRoleName(toRoleName(roleCode));
            permissionRoleMapper.insertRole(role);
            isNewRole = true;
        }
        if (isNewRole) {
            linkPermissionsToRole(role.getId(), roleCode);
        }
        permissionRoleMapper.insertUserRole(userId, role.getId());
    }

    private void linkPermissionsToRole(int roleId, String roleCode) {
        boolean isAdmin = SystemRoleCodes.SYSTEM_ADMIN.equals(roleCode);
        List<String> codes = isAdmin
                ? allPermissionCodes()
                : allPermissionCodes().stream().filter(BASIC_PERMISSIONS::contains).toList();
        List<Integer> permissionIds = BatchPermissionHelper.resolvePermissionIds(
                codes,
                permissionRoleMapper::getPermissionIdsByCodes,
                uno.acloud.team.entity.PermissionEntity::getId,
                uno.acloud.team.entity.PermissionEntity::getPermissionCode
        );
        if (!permissionIds.isEmpty()) {
            permissionRoleMapper.batchInsertRolePermissionsIgnore(roleId, permissionIds);
        }
    }

    private static List<String> allPermissionCodes() {
        return SystemPermissionCodes.allCodes();
    }

    private static String toRoleName(String roleCode) {
        return switch (roleCode) {
            case SystemRoleCodes.SYSTEM_ADMIN -> "系统管理员";
            case SystemRoleCodes.SYSTEM_USER -> "普通用户";
            default -> roleCode;
        };
    }

    /**
     * 发布用户权限/角色变更失效通知（跨节点秒级失效）。
     * 通过 Redis Pub/Sub 频道 {@link PermissionCache#INVALIDATION_TOPIC} 广播 userId，
     * 各消费服务的 {@code PermissionCache} 订阅后清除该用户本地缓存。
     * Redis 不可用时静默降级，不影响主流程（5 分钟 TTL 兜底）。
     */
    private void publishPermissionInvalidation(Long userId) {
        try {
            stringRedisTemplate.convertAndSend(PermissionCache.INVALIDATION_TOPIC, String.valueOf(userId));
        } catch (Exception e) {
            log.warn("Failed to publish permission invalidation for user {} after commit", userId, e);
        }
    }

    private static final Set<String> BASIC_PERMISSIONS = Set.of(
            SystemPermissionCodes.FILE_READ,
            SystemPermissionCodes.FILE_UPLOAD,
            SystemPermissionCodes.FILE_WRITE,
            SystemPermissionCodes.FILE_DELETE,
            SystemPermissionCodes.FOLDER_CREATE,
            SystemPermissionCodes.TRASH_READ,
            SystemPermissionCodes.SHARE_CREATE,
            SystemPermissionCodes.SHARE_READ
    );
}
