package uno.acloud.team.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.util.BatchPermissionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.PermissionRoleMapper;

import java.util.List;
import java.util.Set;

import static uno.acloud.common.InputNormalizer.requireText;

/**
 * 用户角色绑定服务
 * 负责系统级用户角色分配、默认角色初始化
 */
@Service
public class UserRoleBindingService {

    private final PermissionRoleMapper permissionRoleMapper;
    private final UserServiceClient userServiceClient;
    private final AuditLogService auditLogService;

    public UserRoleBindingService(PermissionRoleMapper permissionRoleMapper,
                                  UserServiceClient userServiceClient,
                                  AuditLogService auditLogService) {
        this.permissionRoleMapper = permissionRoleMapper;
        this.userServiceClient = userServiceClient;
        this.auditLogService = auditLogService;
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
        userServiceClient.clearPermissionCache(userId);
    }

    /** 为新用户分配默认角色（首个用户为管理员，后续为普通用户） */
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultRole(Long userId, String username) {
        if (userId == null || permissionRoleMapper.countUserRoles(userId) > 0) {
            return;
        }
        boolean hasAdmin = permissionRoleMapper.countUsersByRoleCode(SystemRoleCodes.SYSTEM_ADMIN) > 0;
        String roleCode = hasAdmin ? SystemRoleCodes.SYSTEM_USER : SystemRoleCodes.SYSTEM_ADMIN;
        assignRoleByCode(userId, roleCode);
        userServiceClient.clearPermissionCache(userId);
    }

    /** 引导阶段分配管理员角色 */
    @Transactional(rollbackFor = Exception.class)
    public void assignBootstrapAdminRole(Long userId) {
        if (userId == null || permissionRoleMapper.countUserRoles(userId) > 0) {
            return;
        }
        assignRoleByCode(userId, SystemRoleCodes.SYSTEM_ADMIN);
        userServiceClient.clearPermissionCache(userId);
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
