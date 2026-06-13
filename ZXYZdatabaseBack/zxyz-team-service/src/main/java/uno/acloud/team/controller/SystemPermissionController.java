package uno.acloud.team.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.team.dto.permission.RolePermissionAssignRequest;
import uno.acloud.team.vo.permission.PermissionAuditVO;
import uno.acloud.team.vo.permission.PermissionItemVO;
import uno.acloud.team.vo.permission.RoleItemVO;
import uno.acloud.team.dto.permission.RoleUpsertRequest;
import uno.acloud.team.dto.permission.UserRoleAssignRequest;
import uno.acloud.team.service.PermissionPort;

import java.util.List;

@Tag(name = "系统权限管理", description = "系统级角色与权限管理")
@RestController
@RequestMapping("/api/permissions")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class SystemPermissionController {

    private final PermissionPort permissionPort;

    public SystemPermissionController(PermissionPort permissionPort) {
        this.permissionPort = permissionPort;
    }

    @Operation(summary = "查询系统权限列表")
    @GetMapping
    public Result<List<PermissionItemVO>> listSystemPermissions() {
        return Result.of(permissionPort.listSystemPermissions());
    }

    @Operation(summary = "查询系统角色列表")
    @GetMapping("/roles")
    public Result<List<RoleItemVO>> listSystemRoles() {
        return Result.of(permissionPort.listSystemRoles());
    }

    @Operation(summary = "创建系统角色")
    @PostMapping("/roles")
    public Result<RoleItemVO> createSystemRole(@CurrentUser Long userId, @Valid @RequestBody RoleUpsertRequest request, HttpServletRequest httpRequest) {
        return Result.of(permissionPort.saveSystemRole(request, userId, null, httpRequest.getRemoteAddr()));
    }

    @Operation(summary = "更新系统角色")
    @PatchMapping("/roles/{roleId}")
    public Result<RoleItemVO> updateSystemRole(@CurrentUser Long userId, @PathVariable Integer roleId, @Valid @RequestBody RoleUpsertRequest request, HttpServletRequest httpRequest) {
        return Result.of(permissionPort.saveSystemRole(request, userId, roleId, httpRequest.getRemoteAddr()));
    }

    @Operation(summary = "删除系统角色")
    @DeleteMapping("/roles/{roleId}")
    public Result<Void> deleteSystemRole(@CurrentUser Long userId, @PathVariable Integer roleId, HttpServletRequest httpRequest) {
        permissionPort.deleteSystemRole(roleId, userId, httpRequest.getRemoteAddr());
        return Result.success();
    }

    @Operation(summary = "分配角色权限")
    @PostMapping("/roles/{roleId}/permissions")
    public Result<Void> assignRolePermissions(@CurrentUser Long userId, @PathVariable Integer roleId, @Valid @RequestBody RolePermissionAssignRequest request, HttpServletRequest httpRequest) {
        permissionPort.assignPermissionsToRole(roleId, request, userId, httpRequest.getRemoteAddr());
        return Result.success();
    }

    @Operation(summary = "分配用户系统角色")
    @PostMapping("/users/{userId}/roles")
    public Result<Void> assignUserRole(@CurrentUser Long operatorId, @PathVariable Long userId, @Valid @RequestBody UserRoleAssignRequest request, HttpServletRequest httpRequest) {
        permissionPort.assignRoleToUser(userId, request.getRoleCode(), operatorId, httpRequest.getRemoteAddr());
        return Result.success();
    }

    @Operation(summary = "查询系统权限审计日志")
    @GetMapping("/audit")
    public Result<List<PermissionAuditVO>> listSystemAudit() {
        return Result.of(permissionPort.listSystemAudit(50));
    }
}
