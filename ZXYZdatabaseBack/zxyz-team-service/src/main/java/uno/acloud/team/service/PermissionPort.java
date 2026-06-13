package uno.acloud.team.service;

import uno.acloud.team.dto.permission.RolePermissionAssignRequest;
import uno.acloud.team.dto.permission.RoleUpsertRequest;
import uno.acloud.team.vo.permission.PermissionAuditVO;
import uno.acloud.team.vo.permission.PermissionItemVO;
import uno.acloud.team.vo.permission.RoleItemVO;

import java.util.List;

public interface PermissionPort {

    List<String> getSystemRolesByUserId(Long userId);

    List<String> getSystemPermissionsByUserId(Long userId);

    List<String> getTeamRolesByUserIdAndTeamId(Long userId, Long teamId);

    List<String> getTeamPermissionsByUserIdAndTeamId(Long userId, Long teamId);

    boolean hasTeamPermission(Long userId, Long teamId, String permissionCode);

    List<PermissionItemVO> listSystemPermissions();

    List<RoleItemVO> listSystemRoles();

    RoleItemVO saveSystemRole(RoleUpsertRequest request, Long operatorId, Integer roleId, String ipAddress);

    void deleteSystemRole(Integer roleId, Long operatorId, String ipAddress);

    void assignPermissionsToRole(Integer roleId, RolePermissionAssignRequest request, Long operatorId, String ipAddress);

    void assignRoleToUser(Long userId, String roleCode, Long operatorId, String ipAddress);

    void ensureDefaultRole(Long userId, String username);

    void assignBootstrapAdminRole(Long userId);

    List<PermissionAuditVO> listSystemAudit(int limit);

    List<Long> listSystemAdminUserIds();
}
