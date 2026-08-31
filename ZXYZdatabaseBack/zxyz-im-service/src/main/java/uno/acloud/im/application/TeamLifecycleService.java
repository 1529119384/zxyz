package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;

import java.util.Map;

@Service
public class TeamLifecycleService {

    private final TeamService teamService;
    private final TeamMapper teamMapper;
    private final ConversationMapper conversationMapper;
    private final SystemNotificationService notificationService;
    private final ImDomainEventPublisher domainEventPublisher;
    private final TeamPermissionService teamPermissionService;

    public TeamLifecycleService(TeamService teamService,
                                TeamMapper teamMapper,
                                ConversationMapper conversationMapper,
                                SystemNotificationService notificationService,
                                ImDomainEventPublisher domainEventPublisher,
                                TeamPermissionService teamPermissionService) {
        this.teamService = teamService;
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.notificationService = notificationService;
        this.domainEventPublisher = domainEventPublisher;
        this.teamPermissionService = teamPermissionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public void leaveTeam(Long userId, Long teamId) {
        TeamMember member = teamService.requireActiveMember(teamId, userId);
        if (TeamRoleCodes.OWNER.equals(member.getRoleCode()) && teamMapper.countActiveOwners(teamId) <= 1) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "最后一个大管理员不能直接退出团队");
        }
        deactivateTeamAccess(teamId, userId);
        notifyManagers(teamId, SystemNotificationType.TEAM_MEMBER_LEFT, "成员已退出团队", "有成员退出了团队。", userId.longValue());
        domainEventPublisher.publish(ImDomainEventType.TEAM_MEMBER_LEFT, Map.of(
                "teamId", teamId,
                "userId", userId
        ));
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long operatorUserId, Long teamId, Long targetUserId) {
        if (operatorUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能移除自己，请使用退出团队");
        }
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);
        TeamMember target = teamService.requireActiveMember(teamId, targetUserId);
        deactivateTeamAccess(teamId, targetUserId);
        notificationService.createNotification(
                targetUserId,
                SystemNotificationType.TEAM_MEMBER_REMOVED,
                "你已被移出团队",
                "管理员已将你移出团队，相关团队会话不再可见。",
                SystemNotificationType.TEAM_MEMBER_REMOVED,
                teamId,
                teamId
        );
        notifyManagers(teamId, SystemNotificationType.TEAM_MEMBER_REMOVED, "成员已被移除", "有成员被移出了团队。", targetUserId.longValue());
        domainEventPublisher.publish(ImDomainEventType.TEAM_MEMBER_REMOVED, Map.of(
                "teamId", teamId,
                "operatorUserId", operatorUserId,
                "targetUserId", targetUserId
        ));
    }

    private void deactivateTeamAccess(Long teamId, Long userId) {
        teamMapper.deactivateMember(teamId, userId);
        teamPermissionService.clearMemberRole(teamId, userId);
        conversationMapper.deactivateUserConversationsInTeam(teamId, userId);
    }

    private void notifyManagers(Long teamId, String type, String title, String content, Long businessId) {
        for (Long memberUserId : teamMapper.listActiveMemberUserIds(teamId)) {
            TeamMember member = teamMapper.getActiveMember(teamId, memberUserId);
            if (member != null && teamPermissionService.hasPermission(teamId, memberUserId, TeamPermissionCodes.TEAM_MEMBER_REMOVE)) {
                notificationService.createNotification(memberUserId, type, title, content, type, businessId, teamId);
            }
        }
    }
}
