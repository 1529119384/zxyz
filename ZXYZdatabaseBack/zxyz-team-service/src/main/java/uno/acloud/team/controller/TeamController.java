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
import uno.acloud.common.Result;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.common.oss.AvatarUploadSignRequest;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.common.permission.RequiresTeamPermission;
import uno.acloud.team.vo.team.TeamMemberStorageVO;
import uno.acloud.team.vo.team.TeamMemberVO;
import uno.acloud.team.vo.team.TeamVO;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.UpdateTeamMemberPersonalStorageRequest;
import uno.acloud.team.dto.team.UpdateTeamMemberStatusRequest;
import uno.acloud.team.dto.team.UpdateTeamRequest;
import uno.acloud.team.service.EnterpriseTeamPort;

import java.util.List;

/**
 * 团队主域 REST 端点。
 */
@Tag(name = "团队管理", description = "团队 CRUD、成员管理")
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final EnterpriseTeamPort teamPort;

    public TeamController(EnterpriseTeamPort teamPort) {
        this.teamPort = teamPort;
    }

    @Operation(summary = "查询我的团队列表")
    @GetMapping("/my")
    public Result<List<TeamVO>> listMyTeams(@CurrentUser Long userId) {
        return Result.of(teamPort.listMyTeams(userId));
    }

    @Operation(summary = "更新团队信息")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_UPDATE)
    @PatchMapping("/{teamId}")
    public Result<TeamVO> updateTeam(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody UpdateTeamRequest request) {
        return Result.of(teamPort.updateTeam(teamId, request, userId));
    }

    @Operation(summary = "获取团队头像上传签名")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_UPDATE)
    @PostMapping("/{teamId}/avatar/upload-sign")
    public Result<OssSignInfo> getAvatarUploadSign(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody AvatarUploadSignRequest request) {
        return Result.of(teamPort.getAvatarUploadSign(teamId, request, userId));
    }

    @Operation(summary = "查询团队成员列表")
    @GetMapping("/{teamId}/members")
    public Result<List<TeamMemberVO>> listMembers(@CurrentUser Long userId, @PathVariable Long teamId) {
        return Result.of(teamPort.listMembers(teamId, userId));
    }

    @Operation(summary = "添加团队成员")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_MEMBER_CREATE)
    @PostMapping("/{teamId}/members")
    public Result<TeamMemberVO> createMember(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody CreateTeamMemberRequest request) {
        return Result.of(teamPort.createMember(teamId, request, userId));
    }

    @Operation(summary = "更新团队成员状态")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_MEMBER_ASSIGN_ROLE)
    @PatchMapping("/{teamId}/members/{userId}/status")
    public Result<TeamMemberVO> updateMemberStatus(@CurrentUser Long operatorId,
                                     @PathVariable Long teamId,
                                     @PathVariable Long userId,
                                     @Valid @RequestBody UpdateTeamMemberStatusRequest request) {
        return Result.of(teamPort.updateMemberStatus(teamId, userId, request, operatorId));
    }

    @Operation(summary = "移除团队成员")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_MEMBER_REMOVE)
    @DeleteMapping("/{teamId}/members/{userId}")
    public Result<Void> removeMember(@CurrentUser Long operatorId, @PathVariable Long teamId, @PathVariable Long userId) {
        teamPort.removeMember(teamId, userId, operatorId);
        return Result.success();
    }

    @Operation(summary = "退出团队")
    @PostMapping("/{teamId}/leave")
    public Result<Void> leaveTeam(@CurrentUser Long userId, @PathVariable Long teamId) {
        teamPort.leaveTeam(teamId, userId);
        return Result.success();
    }

    @Operation(summary = "查询团队成员存储用量")
    @GetMapping("/{teamId}/members/storage")
    public Result<List<TeamMemberStorageVO>> listMembersStorageUsage(@CurrentUser Long userId, @PathVariable Long teamId) {
        return Result.of(teamPort.listMembersStorageUsage(teamId, userId));
    }

    @Operation(summary = "更新成员个人存储配额")
    @RequiresTeamPermission(TeamPermissionCodes.TEAM_STORAGE_ALLOCATE)
    @PatchMapping("/{teamId}/members/{userId}/storage")
    public Result<Void> updateMemberPersonalStorageLimit(@CurrentUser Long operatorId,
                                                   @PathVariable Long teamId,
                                                   @PathVariable Long userId,
                                                   @Valid @RequestBody UpdateTeamMemberPersonalStorageRequest request) {
        Long personalStorageLimit = request == null ? null : request.getPersonalStorageLimit();
        teamPort.updateMemberPersonalStorageLimit(teamId, userId, personalStorageLimit, operatorId);
        return Result.success();
    }
}
