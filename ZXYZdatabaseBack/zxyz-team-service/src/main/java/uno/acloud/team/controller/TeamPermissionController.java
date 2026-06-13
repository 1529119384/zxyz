package uno.acloud.team.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.team.vo.permission.TeamPermissionAuditVO;
import uno.acloud.team.vo.permission.TeamPermissionVO;
import uno.acloud.team.vo.permission.TeamRoleVO;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.permission.RequiresTeamPermission;
import uno.acloud.team.dto.permission.AssignTeamMemberRoleRequest;
import uno.acloud.team.dto.permission.AssignTeamRolePermissionsRequest;
import uno.acloud.team.dto.permission.SaveTeamRoleRequest;
import uno.acloud.team.service.impl.RoleManagementService;
import uno.acloud.team.service.impl.TeamRolePermissionService;

import java.util.List;

/**
 * 团队权限管理 REST 端点（前端调用）
 * 路径与原 IM Service 的 TeamPermissionController 一致，Gateway 已将 /api/permissions/** 路由到此服务
 */
@Tag(name = "团队权限管理", description = "团队级角色与权限管理")
@RestController
@RequestMapping("/api/permissions/teams/{teamId}")
public class TeamPermissionController {

    private final TeamRolePermissionService teamRolePermissionService;
    private final RoleManagementService roleManagementService;

    public TeamPermissionController(TeamRolePermissionService teamRolePermissionService,
                                    RoleManagementService roleManagementService) {
        this.teamRolePermissionService = teamRolePermissionService;
        this.roleManagementService = roleManagementService;
    }

    @Operation(summary = "查询团队权限列表")
    @GetMapping("/permissions")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_PERMISSION_READ)
    public Result<List<TeamPermissionVO>> listPermissions(@PathVariable Long teamId) {
        return Result.of(teamRolePermissionService.listPermissions());
    }

    @Operation(summary = "查询团队角色列表")
    @GetMapping("/roles")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_PERMISSION_READ)
    public Result<List<TeamRoleVO>> listRoles(@PathVariable Long teamId) {
        return Result.of(roleManagementService.listRoles(teamId));
    }

    @Operation(summary = "创建团队角色")
    @PostMapping("/roles")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_ROLE_MANAGE)
    public Result<TeamRoleVO> createRole(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody SaveTeamRoleRequest request) {
        return Result.of(roleManagementService.saveRole(teamId, null, request, userId));
    }

    @Operation(summary = "更新团队角色")
    @PatchMapping("/roles/{roleId}")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_ROLE_MANAGE)
    public Result<TeamRoleVO> updateRole(@CurrentUser Long userId, @PathVariable Long teamId, @PathVariable Long roleId, @Valid @RequestBody SaveTeamRoleRequest request) {
        return Result.of(roleManagementService.saveRole(teamId, roleId, request, userId));
    }

    @Operation(summary = "删除团队角色")
    @DeleteMapping("/roles/{roleId}")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_ROLE_MANAGE)
    public Result<Void> deleteRole(@CurrentUser Long userId, @PathVariable Long teamId, @PathVariable Long roleId) {
        roleManagementService.deleteRole(teamId, roleId, userId);
        return Result.success();
    }

    @Operation(summary = "分配角色权限")
    @PostMapping("/roles/{roleId}/permissions")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_ROLE_MANAGE)
    public Result<Void> assignRolePermissions(@CurrentUser Long userId,
                                        @PathVariable Long teamId,
                                        @PathVariable Long roleId,
                                        @Valid @RequestBody AssignTeamRolePermissionsRequest request) {
        teamRolePermissionService.assignRolePermissions(teamId, roleId, request, userId);
        return Result.success();
    }

    @Operation(summary = "分配成员角色")
    @PostMapping("/member-roles")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_MEMBER_ASSIGN_ROLE)
    public Result<Void> assignMemberRole(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody AssignTeamMemberRoleRequest request) {
        if (request != null && request.getUserId() != null && request.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能给自己调整团队角色");
        }
        roleManagementService.assignMemberRole(teamId, request, userId);
        return Result.success();
    }

    @Operation(summary = "查询团队权限审计日志")
    @GetMapping("/audit")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_AUDIT_READ)
    public Result<List<TeamPermissionAuditVO>> listPermissionAudit(@PathVariable Long teamId) {
        return Result.of(teamRolePermissionService.listAudit(teamId, 50));
    }
}
