package uno.acloud.team.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.permission.AssignTeamRolePermissionsRequest;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;
import uno.acloud.team.vo.permission.TeamPermissionAuditVO;
import uno.acloud.team.vo.permission.TeamPermissionVO;

import java.util.List;

/**
 * 团队级权限服务
 * 负责权限检查、权限定义查询、审计记录查询，以及角色权限分配
 */
@Service
public class TeamRolePermissionService {

    private final TeamPermissionMapper teamPermissionMapper;
    private final TeamPermissionCacheService teamPermissionCacheService;
    private final RoleManagementService roleManagementService;
    private final AuditLogService auditLogService;
    private final TeamEntityMapper teamEntityMapper;

    public TeamRolePermissionService(TeamPermissionMapper teamPermissionMapper,
                                     TeamPermissionCacheService teamPermissionCacheService,
                                     RoleManagementService roleManagementService,
                                     AuditLogService auditLogService,
                                     TeamEntityMapper teamEntityMapper) {
        this.teamPermissionMapper = teamPermissionMapper;
        this.teamPermissionCacheService = teamPermissionCacheService;
        this.roleManagementService = roleManagementService;
        this.auditLogService = auditLogService;
        this.teamEntityMapper = teamEntityMapper;
    }

    // ==================== 权限检查 ====================

    /** 检查成员是否有某权限 */
    public boolean hasPermission(Long teamId, Long userId, String permissionCode) {
        return teamPermissionCacheService.checkPermission(teamId, userId, permissionCode,
                () -> teamPermissionMapper.countMemberPermission(teamId, userId, permissionCode) > 0);
    }

    /** 列出成员所有权限 code */
    public List<String> listMemberPermissionCodes(Long teamId, Long userId) {
        return teamPermissionMapper.listMemberPermissionCodes(teamId, userId);
    }

    /** 获取成员角色 code（委托给 RoleManagementService） */
    public String getMemberRoleCode(Long teamId, Long userId) {
        return roleManagementService.getMemberRoleCode(teamId, userId);
    }

    // ==================== 权限定义 ====================

    /** 列出所有权限定义 */
    public List<TeamPermissionVO> listPermissions() {
        return teamEntityMapper.toTeamPermissionVOList(teamPermissionMapper.listPermissions());
    }

    // ==================== 角色权限分配 ====================

    /** 分配角色权限（带审计） */
    @Transactional(rollbackFor = Exception.class)
    public void assignRolePermissions(Long teamId, Long roleId, AssignTeamRolePermissionsRequest request, Long operatorUserId) {
        roleManagementService.assignRolePermissionsInternal(teamId, roleId, request == null ? List.of() : request.getPermissionCodes());
        auditLogService.writeTeamAudit(teamId, operatorUserId, "team_role:assign-permissions", "team_role", roleId, null,
                String.join(",", request == null || request.getPermissionCodes() == null ? List.of() : request.getPermissionCodes()));
    }

    // ==================== 审计 ====================

    /** 查询审计记录 */
    public List<TeamPermissionAuditVO> listAudit(Long teamId, Integer limit) {
        int safeLimit = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return teamEntityMapper.toTeamPermissionAuditVOList(teamPermissionMapper.listAudit(teamId, safeLimit));
    }

    // ==================== 内部方法（供 IM Service 调用） ====================

    /** 授权内置角色（委托给 RoleManagementService） */
    @Transactional(rollbackFor = Exception.class)
    public void grantBuiltInRole(Long teamId, Long userId, String roleCode) {
        roleManagementService.grantBuiltInRole(teamId, userId, roleCode);
    }

    /** 清除成员角色（委托给 RoleManagementService） */
    @Transactional(rollbackFor = Exception.class)
    public void clearMemberRole(Long teamId, Long userId) {
        roleManagementService.clearMemberRole(teamId, userId);
    }

    /** 初始化团队内置角色（委托给 RoleManagementService） */
    @Transactional(rollbackFor = Exception.class)
    public void initializeBuiltInRoles(Long teamId, Long ownerUserId) {
        roleManagementService.initializeBuiltInRoles(teamId, ownerUserId);
    }
}
