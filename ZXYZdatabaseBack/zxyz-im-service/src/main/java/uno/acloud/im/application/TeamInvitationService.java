package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.domain.enums.ConversationMemberStatus;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.enums.InvitationStatus;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.im.domain.enums.TeamMemberStatus;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.TeamInvitation;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.ImEntityMapper;
import uno.acloud.im.infrastructure.mapper.TeamInvitationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.dto.InviteUserRequest;
import uno.acloud.im.vo.TeamInvitationVO;

import java.time.LocalDateTime;

@Service
public class TeamInvitationService {

    private static final String BUSINESS_TYPE_INVITATION = "TEAM_INVITATION";

    private final TeamService teamService;
    private final UserProfileService userProfileService;
    private final TeamMapper teamMapper;
    private final TeamInvitationMapper invitationMapper;
    private final ConversationMapper conversationMapper;
    private final SystemNotificationService systemNotificationService;
    private final ImDomainEventPublisher domainEventPublisher;
    private final TeamPermissionService teamPermissionService;
    private final ImEntityMapper imEntityMapper;

    public TeamInvitationService(TeamService teamService,
                                 UserProfileService userProfileService,
                                 TeamMapper teamMapper,
                                 TeamInvitationMapper invitationMapper,
                                 ConversationMapper conversationMapper,
                                 SystemNotificationService systemNotificationService,
                                 ImDomainEventPublisher domainEventPublisher,
                                 TeamPermissionService teamPermissionService,
                                 ImEntityMapper imEntityMapper) {
        this.teamService = teamService;
        this.userProfileService = userProfileService;
        this.teamMapper = teamMapper;
        this.invitationMapper = invitationMapper;
        this.conversationMapper = conversationMapper;
        this.systemNotificationService = systemNotificationService;
        this.domainEventPublisher = domainEventPublisher;
        this.teamPermissionService = teamPermissionService;
        this.imEntityMapper = imEntityMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamInvitationVO invite(Long inviterUserId, Long teamId, InviteUserRequest request) {
        Long inviteeUserId = normalizeInviteeUserId(request);
        if (inviterUserId.equals(inviteeUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能邀请自己");
        }

        teamPermissionService.requirePermission(teamId, inviterUserId, TeamPermissionCodes.TEAM_MEMBER_INVITE);
        userProfileService.ensurePlaceholder(inviteeUserId);

        if (teamMapper.getActiveMember(teamId, inviteeUserId) != null) {
            throw new BusinessException(TeamErrorCode.TEAM_MEMBER_EXISTS.getCode(), "用户已在该团队中");
        }

        TeamInvitation existing = invitationMapper.getPendingByTeamAndInvitee(teamId, inviteeUserId);
        if (existing != null) {
            return imEntityMapper.toInvitationVO(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        TeamInvitation invitation = new TeamInvitation();
        invitation.setTeamId(teamId);
        invitation.setInviteeUserId(inviteeUserId);
        invitation.setInviterUserId(inviterUserId);
        invitation.setStatus(InvitationStatus.PENDING);
        invitation.setExpireTime(now.plusDays(7));
        invitation.setCreateTime(now);
        invitationMapper.insertInvitation(invitation);

        systemNotificationService.createNotification(
                inviteeUserId,
                SystemNotificationType.TEAM_INVITATION,
                "团队邀请",
                "你收到了一条团队邀请，请在系统消息中处理。",
                BUSINESS_TYPE_INVITATION,
                invitation.getId(),
                teamId
        );
        domainEventPublisher.publish(ImDomainEventType.TEAM_INVITATION_CREATED, java.util.Map.of(
                "teamId", teamId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId", invitation.getId()
        ));
        return imEntityMapper.toInvitationVO(invitation);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamInvitationVO accept(Long userId, Long invitationId) {
        TeamInvitation invitation = requirePendingInvitation(userId, invitationId);
        if (invitation.getExpireTime() != null && invitation.getExpireTime().isBefore(LocalDateTime.now())) {
            invitationMapper.updateStatus(invitationId, InvitationStatus.EXPIRED);
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_EXPIRED.getCode(), "邀请已过期");
        }

        if (invitationMapper.updateStatus(invitationId, InvitationStatus.ACCEPTED) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请状态已变化");
        }

        TeamMember member = new TeamMember();
        member.setTeamId(invitation.getTeamId());
        member.setUserId(userId);
        member.setRoleCode(TeamRoleCodes.MEMBER);
        member.setStatus(TeamMemberStatus.ACTIVE);
        member.setJoinTime(LocalDateTime.now());
        teamMapper.upsertMember(member);
        teamPermissionService.grantBuiltInRole(invitation.getTeamId(), userId, TeamRoleCodes.MEMBER);

        Long conversationId = conversationMapper.getTeamConversationId(invitation.getTeamId());
        if (conversationId == null) {
            conversationId = createMissingTeamConversation(invitation.getTeamId());
        }
        conversationMapper.upsertConversationMember(conversationId, userId);
        systemNotificationService.markBusinessRead(userId, BUSINESS_TYPE_INVITATION, invitationId);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setHandleTime(LocalDateTime.now());
        return imEntityMapper.toInvitationVO(invitation);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamInvitationVO reject(Long userId, Long invitationId) {
        TeamInvitation invitation = requirePendingInvitation(userId, invitationId);
        if (invitationMapper.updateStatus(invitationId, InvitationStatus.REJECTED) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请状态已变化");
        }
        systemNotificationService.markBusinessRead(userId, BUSINESS_TYPE_INVITATION, invitationId);
        invitation.setStatus(InvitationStatus.REJECTED);
        invitation.setHandleTime(LocalDateTime.now());
        return imEntityMapper.toInvitationVO(invitation);
    }

    private TeamInvitation requirePendingInvitation(Long userId, Long invitationId) {
        if (invitationId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "invitationId 不能为空");
        }
        TeamInvitation invitation = invitationMapper.selectById(invitationId);
        if (invitation == null || !userId.equals(invitation.getInviteeUserId())) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请不存在");
        }
        if (!Integer.valueOf(InvitationStatus.PENDING).equals(invitation.getStatus())) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请已处理");
        }
        return invitation;
    }

    private Long normalizeInviteeUserId(InviteUserRequest request) {
        if (request == null || request.getUserId() == null || request.getUserId() < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        return request.getUserId();
    }

    private Long createMissingTeamConversation(Long teamId) {
        LocalDateTime now = LocalDateTime.now();
        ImConversation conversation = new ImConversation();
        conversation.setType(ConversationType.TEAM);
        conversation.setTeamId(teamId);
        conversation.setBizKey("TEAM:" + teamId);
        conversation.setStatus(ConversationMemberStatus.ACTIVE);
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        conversationMapper.insertConversation(conversation);
        return conversation.getId();
    }

}
