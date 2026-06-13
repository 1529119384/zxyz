package uno.acloud.team.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.permission.AssignTeamMemberRoleRequest;
import uno.acloud.team.vo.permission.PermissionAuditVO;
import uno.acloud.team.vo.permission.PermissionItemVO;
import uno.acloud.team.vo.permission.RoleItemVO;
import uno.acloud.team.vo.permission.TeamPermissionAuditVO;
import uno.acloud.team.vo.permission.TeamPermissionVO;
import uno.acloud.team.vo.permission.TeamRoleVO;
import uno.acloud.team.dto.permission.EnsureDefaultRoleRequest;
import uno.acloud.team.dto.permission.GrantRoleRequest;
import uno.acloud.team.dto.permission.InternalRoleAssignRequest;
import uno.acloud.team.dto.permission.InternalRoleSaveRequest;
import uno.acloud.team.dto.permission.InitializeRolesRequest;
import uno.acloud.team.dto.permission.PermissionCheckRequest;
import uno.acloud.team.dto.permission.SaveTeamRoleRequest;
import uno.acloud.team.dto.permission.TeamMemberRequest;
import uno.acloud.team.dto.permission.UserIdRequest;
import uno.acloud.team.service.PermissionPort;
import uno.acloud.team.service.impl.RoleManagementService;
import uno.acloud.team.service.impl.TeamRolePermissionService;

import java.util.List;

/**
 * 内部权限端点（供其他微服务通过 X-Internal-Service-Token 调用）
 */
@Hidden
@Tag(name = "权限管理（内部）", description = "内部服务权限查询与管理 API")
@RestController
@RequestMapping("/api/internal/permissions")
public class InternalPermissionController {

    private final PermissionPort permissionService;
    private final TeamRolePermissionService teamRolePermissionService;
    private final RoleManagementService roleManagementService;

    public InternalPermissionController(PermissionPort permissionService,
                                        TeamRolePermissionService teamRolePermissionService,
                                        RoleManagementService roleManagementService) {
        this.permissionService = permissionService;
        this.teamRolePermissionService = teamRolePermissionService;
        this.roleManagementService = roleManagementService;
    }

    // ==================== 系统级权限（已有） ====================

    @Operation(summary = "校验团队权限（不足则抛异常）")
    @PostMapping("/check")
    public Result<Void> check(@Valid @RequestBody PermissionCheckRequest request) {
        boolean allowed = permissionService.hasTeamPermission(request.getUserId(), request.getTeamId(), request.getPermissionCode());
        if (!allowed) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "缺少团队权限: " + request.getPermissionCode());
        }
        return Result.success();
    }

    @Operation(summary = "查询是否拥有团队权限")
    @PostMapping("/has")
    public Result<Boolean> hasPermission(@Valid @RequestBody PermissionCheckRequest request) {
        return Result.of(permissionService.hasTeamPermission(request.getUserId(), request.getTeamId(), request.getPermissionCode()));
    }

    @Operation(summary = "查询系统权限列表")
    @GetMapping("/system-permissions")
    public Result<List<PermissionItemVO>> listSystemPermissions() {
        return Result.of(permissionService.listSystemPermissions());
    }

    @Operation(summary = "查询系统角色列表")
    @GetMapping("/system-roles")
    public Result<List<RoleItemVO>> listSystemRoles() {
        return Result.of(permissionService.listSystemRoles());
    }

    @Operation(summary = "查询系统权限审计日志")
    @GetMapping("/system-audit")
    public Result<List<PermissionAuditVO>> listSystemAudit() {
        return Result.of(permissionService.listSystemAudit(50));
    }

    @Operation(summary = "创建或更新系统角色")
    @PostMapping("/system-roles")
    public Result<RoleItemVO> saveSystemRole(@Valid @RequestBody InternalRoleSaveRequest request) {
        return Result.of(permissionService.saveSystemRole(
                request, request.getOperatorId(), request.getRoleId(), request.getIpAddress()));
    }

    @Operation(summary = "分配系统角色给用户")
    @PostMapping("/system-roles/assign")
    public Result<Void> assignRoleToUser(@Valid @RequestBody InternalRoleAssignRequest request) {
        permissionService.assignRoleToUser(
                request.getUserId(), request.getRoleCode(), request.getOperatorId(), request.getIpAddress());
        return Result.success();
    }

    // ==================== 团队级角色管理（已有桩方法，现已实现） ====================

    @Operation(summary = "创建或更新团队角色")
    @PostMapping("/team-roles")
    public Result<TeamRoleVO> saveTeamRole(@Valid @RequestBody SaveTeamRoleRequest request) {
        return Result.of(roleManagementService.saveRole(request.getTeamId(), null, request, null));
    }

    @Operation(summary = "查询团队角色列表")
    @GetMapping("/team-roles/{teamId}")
    public Result<List<TeamRoleVO>> listTeamRoles(@PathVariable Long teamId) {
        return Result.of(roleManagementService.listRoles(teamId));
    }

    @Operation(summary = "分配团队角色给成员")
    @PostMapping("/team-roles/assign")
    public Result<Void> assignTeamRole(@Valid @RequestBody AssignTeamMemberRoleRequest request) {
        roleManagementService.assignMemberRole(request.getTeamId(), request, null);
        return Result.success();
    }

    // ==================== 用户角色查询（已有） ====================

    @Operation(summary = "查询用户的系统角色列表")
    @GetMapping("/user/system-roles/{userId}")
    public Result<List<String>> getSystemRolesByUserId(@PathVariable Long userId) {
        return Result.of(permissionService.getSystemRolesByUserId(userId));
    }

    @Operation(summary = "查询系统管理员用户ID列表")
    @GetMapping("/user/system-admin-ids")
    public Result<List<Long>> listSystemAdminUserIds() {
        return Result.of(permissionService.listSystemAdminUserIds());
    }

    @Operation(summary = "查询用户的系统权限列表")
    @GetMapping("/user/system-permissions/{userId}")
    public Result<List<String>> getSystemPermissionsByUserId(@PathVariable Long userId) {
        return Result.of(permissionService.getSystemPermissionsByUserId(userId));
    }

    @Operation(summary = "查询用户在团队中的角色列表")
    @GetMapping("/user/team-roles/{teamId}/{userId}")
    public Result<List<String>> getTeamRolesByUserIdAndTeamId(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(permissionService.getTeamRolesByUserIdAndTeamId(userId, teamId));
    }

    @Operation(summary = "查询用户在团队中的权限列表")
    @GetMapping("/user/team-permissions/{teamId}/{userId}")
    public Result<List<String>> getTeamPermissionsByUserIdAndTeamId(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(permissionService.getTeamPermissionsByUserIdAndTeamId(userId, teamId));
    }

    @Operation(summary = "确保用户拥有默认角色")
    @PostMapping("/ensure-default-role")
    public Result<Void> ensureDefaultRole(@Valid @RequestBody EnsureDefaultRoleRequest request) {
        permissionService.ensureDefaultRole(request.getUserId(), request.getUsername());
        return Result.success();
    }

    @Operation(summary = "分配引导管理员角色")
    @PostMapping("/assign-bootstrap-admin")
    public Result<Void> assignBootstrapAdminRole(@Valid @RequestBody UserIdRequest request) {
        permissionService.assignBootstrapAdminRole(request.getUserId());
        return Result.success();
    }

    // ==================== 新增：团队级权限内部端点（供 IM Service 调用） ====================

    /** 检查成员是否有某团队权限 */
    @Operation(summary = "检查成员团队权限")
    @PostMapping("/team/check")
    public Result<Boolean> checkTeamPermission(@Valid @RequestBody PermissionCheckRequest request) {
        return Result.of(teamRolePermissionService.hasPermission(request.getTeamId(), request.getUserId(), request.getPermissionCode()));
    }

    /** 授权内置角色（供 IM Service 在成员同步时调用） */
    @Operation(summary = "授权内置角色")
    @PostMapping("/team/grant-role")
    public Result<Void> grantBuiltInRole(@Valid @RequestBody GrantRoleRequest request) {
        roleManagementService.grantBuiltInRole(request.getTeamId(), request.getUserId(), request.getRoleCode());
        return Result.success();
    }

    /** 清除成员角色（供 IM Service 在成员移除时调用） */
    @Operation(summary = "清除成员角色")
    @PostMapping("/team/clear-role")
    public Result<Void> clearMemberRole(@Valid @RequestBody TeamMemberRequest request) {
        roleManagementService.clearMemberRole(request.getTeamId(), request.getUserId());
        return Result.success();
    }

    /** 初始化内置角色（供 IM Service 在团队同步时调用） */
    @Operation(summary = "初始化团队内置角色")
    @PostMapping("/team/initialize")
    public Result<Void> initializeBuiltInRoles(@Valid @RequestBody InitializeRolesRequest request) {
        roleManagementService.initializeBuiltInRoles(request.getTeamId(), request.getOwnerUserId());
        return Result.success();
    }

    /** 列出成员所有权限 code（供 IM Service 调用） */
    @Operation(summary = "列出成员权限编码列表")
    @PostMapping("/team/list-permissions")
    public Result<List<String>> listMemberPermissions(@Valid @RequestBody TeamMemberRequest request) {
        return Result.of(teamRolePermissionService.listMemberPermissionCodes(request.getTeamId(), request.getUserId()));
    }

    /** 获取成员角色 code（供 IM Service 调用） */
    @Operation(summary = "获取成员角色编码")
    @PostMapping("/team/role-code")
    public Result<String> getMemberRoleCode(@Valid @RequestBody TeamMemberRequest request) {
        return Result.of(roleManagementService.getMemberRoleCode(request.getTeamId(), request.getUserId()));
    }
}
