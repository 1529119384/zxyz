package uno.acloud.team.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.util.BatchPermissionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.permission.RolePermissionAssignRequest;
import uno.acloud.team.dto.permission.RoleUpsertRequest;
import uno.acloud.team.entity.PermissionEntity;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.mapper.PermissionRoleMapper;
import uno.acloud.team.service.PermissionPort;
import uno.acloud.team.vo.permission.PermissionAuditVO;
import uno.acloud.team.vo.permission.PermissionItemVO;
import uno.acloud.team.vo.permission.RoleItemVO;

import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

/**
 * 系统级权限服务
 * 负责权限检查、角色 CRUD、权限分配、审计记录查询
 * 用户角色绑定操作委托给 {@link UserRoleBindingService}
 */
@Service
public class PermissionService implements PermissionPort {

    private final PermissionRoleMapper permissionRoleMapper;
    private final TeamPermissionCacheService teamPermissionCacheService;
    private final UserRoleBindingService userRoleBindingService;
    private final AuditLogService auditLogService;
    private final TeamEntityMapper teamEntityMapper;
    private static final List<String> BUILTIN_SYSTEM_ROLE_CODES = List.of(
            SystemRoleCodes.SYSTEM_ADMIN,
            SystemRoleCodes.SYSTEM_USER
    );

    public PermissionService(PermissionRoleMapper permissionRoleMapper,
                             TeamPermissionCacheService teamPermissionCacheService,
                             UserRoleBindingService userRoleBindingService,
                             AuditLogService auditLogService,
                             TeamEntityMapper teamEntityMapper) {
        this.permissionRoleMapper = permissionRoleMapper;
        this.teamPermissionCacheService = teamPermissionCacheService;
        this.userRoleBindingService = userRoleBindingService;
        this.auditLogService = auditLogService;
        this.teamEntityMapper = teamEntityMapper;
    }

    @Override
    public List<String> getSystemRolesByUserId(Long userId) {
        return userRoleBindingService.getSystemRolesByUserId(userId);
    }

    @Override
    public List<String> getSystemPermissionsByUserId(Long userId) {
        return permissionRoleMapper.getPermissionByUserID(userId);
    }

    @Override
    public List<String> getTeamRolesByUserIdAndTeamId(Long userId, Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return permissionRoleMapper.getTeamRoleCodes(userId, teamId);
    }

    @Override
    public List<String> getTeamPermissionsByUserIdAndTeamId(Long userId, Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return permissionRoleMapper.getTeamPermissionCodes(userId, teamId);
    }

    @Override
    public boolean hasTeamPermission(Long userId, Long teamId, String permissionCode) {
        if (teamId == null || userId == null || !StringUtils.hasText(permissionCode)) {
            return false;
        }
        return teamPermissionCacheService.checkPermission(teamId, userId, permissionCode,
                () -> getTeamPermissionsByUserIdAndTeamId(userId, teamId).contains(permissionCode));
    }

    @Override
    public List<PermissionItemVO> listSystemPermissions() {
        return teamEntityMapper.toPermissionItemVOList(permissionRoleMapper.listPermissions());
    }

    @Override
    public List<RoleItemVO> listSystemRoles() {
        return permissionRoleMapper.listRoles().stream()
                .map(role -> {
                    RoleItemVO vo = teamEntityMapper.toRoleItemVO(role);
                    vo.setPermissionCodes(permissionRoleMapper.listPermissionCodesByRoleId(role.getId()));
                    return vo;
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleItemVO saveSystemRole(RoleUpsertRequest request, Long operatorId, Integer roleId, String ipAddress) {
        String roleCode = requireText(request == null ? null : request.getRoleCode(), "roleCode 不能为空");
        String roleName = requireText(request == null ? null : request.getRoleName(), "roleName 不能为空");
        String description = optionalText(request == null ? null : request.getDescription());
        RoleEntity saved;
        if (roleId == null) {
            if (permissionRoleMapper.getRoleByCode(roleCode) != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "角色编码已存在");
            }
            RoleEntity role = new RoleEntity();
            role.setRoleCode(roleCode);
            role.setRoleName(roleName);
            role.setDescription(description);
            permissionRoleMapper.insertRole(role);
            auditLogService.writeSystemAudit(operatorId, "system", "role:create", "role", Long.valueOf(role.getId()), null, roleCode, ipAddress);
            saved = role;
        } else {
            RoleEntity existing = requireRole(roleId);
            existing.setRoleName(roleName);
            existing.setDescription(description);
            permissionRoleMapper.updateRole(existing);
            auditLogService.writeSystemAudit(operatorId, "system", "role:update", "role", Long.valueOf(existing.getId()), existing.getRoleCode(), existing.getRoleCode(), ipAddress);
            saved = existing;
        }
        RoleItemVO vo = teamEntityMapper.toRoleItemVO(saved);
        vo.setPermissionCodes(permissionRoleMapper.listPermissionCodesByRoleId(saved.getId()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSystemRole(Integer roleId, Long operatorId, String ipAddress) {
        RoleEntity role = requireRole(roleId);
        if (BUILTIN_SYSTEM_ROLE_CODES.contains(role.getRoleCode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内置角色不允许删除");
        }
        if (permissionRoleMapper.countUsersByRoleId(roleId) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色已被用户使用，不能删除");
        }
        List<String> beforeCodes = permissionRoleMapper.listPermissionCodesByRoleId(roleId);
        permissionRoleMapper.deleteRolePermissions(roleId);
        permissionRoleMapper.deleteRole(roleId);
        auditLogService.writeSystemAudit(operatorId, "system", "role:delete", "role", Long.valueOf(roleId),
                role.getRoleCode() + ":" + String.join(",", beforeCodes), null, ipAddress);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissionsToRole(Integer roleId, RolePermissionAssignRequest request, Long operatorId, String ipAddress) {
        RoleEntity role = requireRole(roleId);
        List<String> beforeCodes = permissionRoleMapper.listPermissionCodesByRoleId(roleId);
        permissionRoleMapper.deleteRolePermissions(roleId);
        List<String> permissionCodes = request == null || request.getPermissionCodes() == null ? List.of() : request.getPermissionCodes();
        List<Integer> permissionIds = BatchPermissionHelper.resolvePermissionIds(
                permissionCodes,
                permissionRoleMapper::getPermissionIdsByCodes,
                PermissionEntity::getId,
                PermissionEntity::getPermissionCode
        );
        if (!permissionIds.isEmpty()) {
            permissionRoleMapper.batchInsertRolePermissions(roleId, permissionIds);
        }
        auditLogService.writeSystemAudit(operatorId, "system", "role:assign-permissions", "role", Long.valueOf(roleId),
                String.join(",", beforeCodes), String.join(",", permissionCodes), ipAddress);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoleToUser(Long userId, String roleCode, Long operatorId, String ipAddress) {
        userRoleBindingService.assignRoleToUser(userId, roleCode, operatorId, ipAddress);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ensureDefaultRole(Long userId, String username) {
        userRoleBindingService.ensureDefaultRole(userId, username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignBootstrapAdminRole(Long userId) {
        userRoleBindingService.assignBootstrapAdminRole(userId);
    }

    @Override
    public List<PermissionAuditVO> listSystemAudit(int limit) {
        int safeLimit = limit > 0 ? Math.min(limit, 200) : 50;
        return teamEntityMapper.toPermissionAuditVOList(permissionRoleMapper.listAudit(safeLimit));
    }

    @Override
    public List<Long> listSystemAdminUserIds() {
        return permissionRoleMapper.listUserIdsByRoleCode(SystemRoleCodes.SYSTEM_ADMIN);
    }

    private RoleEntity requireRole(Integer roleId) {
        if (roleId == null || roleId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "角色不存在");
        }
        RoleEntity role = permissionRoleMapper.getRoleById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }
}
