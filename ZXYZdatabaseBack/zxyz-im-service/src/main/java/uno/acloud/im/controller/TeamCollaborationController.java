package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import uno.acloud.common.SystemPermissionCodes;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.AnnouncementService;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.application.JoinRequestService;
import uno.acloud.im.application.MuteService;
import uno.acloud.im.application.TeamInvitationService;
import uno.acloud.im.application.TeamManagementService;
import uno.acloud.im.application.UserProfileService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.dto.CreateInviteLinkRequest;
import uno.acloud.im.dto.InviteUserRequest;
import uno.acloud.im.dto.MuteMemberRequest;
import uno.acloud.im.dto.PublishAnnouncementRequest;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.TeamInvitationVO;
import uno.acloud.im.vo.TeamInviteLinkVO;
import uno.acloud.im.vo.TeamJoinRequestVO;
import uno.acloud.im.vo.TeamMuteVO;
import uno.acloud.im.vo.UserProfileVO;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/team-collaboration")
@Tag(name = "团队协作", description = "邀请、公告、静音、加入申请")
public class TeamCollaborationController {

    private final TeamInvitationService invitationService;
    private final TeamManagementService managementService;
    private final AnnouncementService announcementService;
    private final MuteService muteService;
    private final JoinRequestService joinRequestService;
    private final ImRealtimePushService realtimePushService;
    private final UserProfileService userProfileService;

    public TeamCollaborationController(TeamInvitationService invitationService,
                                       TeamManagementService managementService,
                                       AnnouncementService announcementService,
                                       MuteService muteService,
                                       JoinRequestService joinRequestService,
                                       ImRealtimePushService realtimePushService,
                                       UserProfileService userProfileService) {
        this.invitationService = invitationService;
        this.managementService = managementService;
        this.announcementService = announcementService;
        this.muteService = muteService;
        this.joinRequestService = joinRequestService;
        this.realtimePushService = realtimePushService;
        this.userProfileService = userProfileService;
    }

    @Operation(summary = "搜索用户")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_INVITE)
    @GetMapping("/users/search")
    public Result<List<UserProfileVO>> searchUsers(@RequestParam String keyword) {
        ImAuthContext.currentUserId();
        return Result.of(userProfileService.searchAndSync(keyword));
    }

    @Operation(summary = "邀请用户加入团队")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_INVITE)
    @PostMapping("/teams/{teamId}/invitations")
    public Result<TeamInvitationVO> invite(@PathVariable Long teamId, @Valid @RequestBody InviteUserRequest request) {
        return Result.of(invitationService.invite(ImAuthContext.currentUserId(), teamId, request));
    }

    @Operation(summary = "发布公告")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_ANNOUNCEMENT)
    @PostMapping("/teams/{teamId}/announcements")
    public Result<ImMessageVO> publishAnnouncement(@PathVariable Long teamId, @Valid @RequestBody PublishAnnouncementRequest request) {
        var result = announcementService.publishAnnouncement(ImAuthContext.currentUserId(), teamId, request);
        realtimePushService.pushMessageReceived(result.memberUserIds(), result.message());
        return Result.of(result.message());
    }

    @Operation(summary = "禁言成员")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_MUTE)
    @PostMapping("/teams/{teamId}/mutes")
    public Result<TeamMuteVO> mute(@PathVariable Long teamId, @Valid @RequestBody MuteMemberRequest request) {
        return Result.of(muteService.muteMember(ImAuthContext.currentUserId(), teamId, request));
    }

    @Operation(summary = "获取禁言列表")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_MUTE)
    @GetMapping("/teams/{teamId}/mutes")
    public Result<List<TeamMuteVO>> listMutes(@PathVariable Long teamId) {
        return Result.of(muteService.listMutes(ImAuthContext.currentUserId(), teamId));
    }

    @Operation(summary = "解除禁言")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_MUTE)
    @DeleteMapping("/teams/{teamId}/mutes/{userId}")
    public Result<Void> unmute(@PathVariable Long teamId, @PathVariable Long userId) {
        muteService.unmuteMember(ImAuthContext.currentUserId(), teamId, userId);
        return Result.success();
    }

    @Operation(summary = "创建邀请链接")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_INVITE_LINK)
    @PostMapping("/teams/{teamId}/invite-links")
    public Result<TeamInviteLinkVO> createInviteLink(@PathVariable Long teamId, @Valid @RequestBody(required = false) CreateInviteLinkRequest request) {
        return Result.of(managementService.createInviteLink(ImAuthContext.currentUserId(), teamId, request));
    }

    @Operation(summary = "获取加入申请列表")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_JOIN_REQUEST)
    @GetMapping("/teams/{teamId}/join-requests")
    public Result<List<TeamJoinRequestVO>> listJoinRequests(@PathVariable Long teamId) {
        return Result.of(joinRequestService.listJoinRequests(ImAuthContext.currentUserId(), teamId));
    }

    @Operation(summary = "提交加入申请")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_JOIN_REQUEST)
    @PostMapping("/invite-links/{token}/join-requests")
    public Result<TeamJoinRequestVO> submitJoinRequest(@PathVariable String token) {
        return Result.of(joinRequestService.submitJoinRequest(ImAuthContext.currentUserId(), token));
    }

    @Operation(summary = "批准加入申请")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_JOIN_REQUEST)
    @PostMapping("/join-requests/{requestId}/approve")
    public Result<TeamJoinRequestVO> approveJoinRequest(@PathVariable Long requestId) {
        return Result.of(joinRequestService.approveJoinRequest(ImAuthContext.currentUserId(), requestId));
    }

    @Operation(summary = "拒绝加入申请")
    @SaCheckPermission(SystemPermissionCodes.IM_TEAM_JOIN_REQUEST)
    @PostMapping("/join-requests/{requestId}/reject")
    public Result<TeamJoinRequestVO> rejectJoinRequest(@PathVariable Long requestId) {
        return Result.of(joinRequestService.rejectJoinRequest(ImAuthContext.currentUserId(), requestId));
    }

    @Operation(summary = "接受邀请")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_CREATE)
    @PostMapping("/team-invitations/{invitationId}/accept")
    public Result<TeamInvitationVO> acceptInvitation(@PathVariable Long invitationId) {
        return Result.of(invitationService.accept(ImAuthContext.currentUserId(), invitationId));
    }

    @Operation(summary = "拒绝邀请")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_CREATE)
    @PostMapping("/team-invitations/{invitationId}/reject")
    public Result<TeamInvitationVO> rejectInvitation(@PathVariable Long invitationId) {
        return Result.of(invitationService.reject(ImAuthContext.currentUserId(), invitationId));
    }
}
