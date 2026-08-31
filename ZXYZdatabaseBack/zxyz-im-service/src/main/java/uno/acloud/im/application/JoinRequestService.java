package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.im.domain.enums.TeamMemberStatus;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.infrastructure.persistence.entity.TeamInviteLink;
import uno.acloud.im.infrastructure.persistence.entity.TeamJoinRequest;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.vo.TeamJoinRequestVO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class JoinRequestService {

    private static final int JOIN_REQUEST_PENDING = 0;
    private static final int JOIN_REQUEST_APPROVED = 1;
    private static final int JOIN_REQUEST_REJECTED = 2;

    private final TeamMapper teamMapper;
    private final TeamManagementMapper managementMapper;
    private final ConversationMapper conversationMapper;
    private final TeamPermissionService teamPermissionService;
    private final SystemNotificationService notificationService;
    private final ImDomainEventPublisher domainEventPublisher;

    public JoinRequestService(TeamMapper teamMapper,
                              TeamManagementMapper managementMapper,
                              ConversationMapper conversationMapper,
                              TeamPermissionService teamPermissionService,
                              SystemNotificationService notificationService,
                              ImDomainEventPublisher domainEventPublisher) {
        this.teamMapper = teamMapper;
        this.managementMapper = managementMapper;
        this.conversationMapper = conversationMapper;
        this.teamPermissionService = teamPermissionService;
        this.notificationService = notificationService;
        this.domainEventPublisher = domainEventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamJoinRequestVO submitJoinRequest(Long userId, String token) {
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请链接无效");
        }
        TeamInviteLink link = managementMapper.getInviteLinkByToken(token);
        if (link == null || !Integer.valueOf(0).equals(link.getStatus())) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请链接无效");
        }
        if (link.getExpireTime() != null && link.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_EXPIRED.getCode(), "邀请链接已过期");
        }
        if (link.getMaxUses() != null && link.getMaxUses() > 0 && link.getUsedCount() >= link.getMaxUses()) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请链接使用次数已达上限");
        }
        if (teamMapper.getActiveMember(link.getTeamId(), userId) != null) {
            throw new BusinessException(TeamErrorCode.TEAM_MEMBER_EXISTS.getCode(), "你已在该团队中");
        }
        TeamJoinRequest existing = managementMapper.getPendingJoinRequest(link.getTeamId(), userId);
        if (existing != null) {
            return toJoinRequestVO(existing);
        }
        TeamJoinRequest request = new TeamJoinRequest();
        request.setTeamId(link.getTeamId());
        request.setUserId(userId);
        request.setLinkId(link.getId());
        request.setStatus(JOIN_REQUEST_PENDING);
        request.setCreateTime(LocalDateTime.now());
        managementMapper.insertJoinRequest(request);

        // 批量获取活跃成员，筛选有审核权限的管理员，发送通知
        List<Long> activeMembers = teamMapper.listActiveMemberUserIds(link.getTeamId());
        List<Long> reviewers = teamPermissionService.listUsersWithPermission(
                link.getTeamId(), activeMembers, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        for (Long managerUserId : reviewers) {
            notificationService.createNotification(
                    managerUserId,
                    SystemNotificationType.TEAM_JOIN_REQUEST,
                    "新的团队加入申请",
                    "有用户通过邀请链接申请加入团队，请前往协作页审核。",
                    SystemNotificationType.TEAM_JOIN_REQUEST,
                    request.getId(),
                    link.getTeamId()
            );
        }
        // MQ publish deferred to afterCommit to avoid holding DB connection during remote I/O
        Long teamId = request.getTeamId();
        Long requestId = request.getId();
        Long linkId = request.getLinkId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                domainEventPublisher.publish(ImDomainEventType.TEAM_JOIN_REQUEST_SUBMITTED, Map.of(
                        "teamId", teamId,
                        "userId", userId,
                        "requestId", requestId,
                        "linkId", linkId
                ));
            }
        });
        return toJoinRequestVO(request);
    }

    public List<TeamJoinRequestVO> listJoinRequests(Long operatorUserId, Long teamId) {
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        return managementMapper.listPendingJoinRequests(teamId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamJoinRequestVO approveJoinRequest(Long operatorUserId, Long requestId) {
        TeamJoinRequest request = requirePendingJoinRequest(requestId);
        teamPermissionService.requirePermission(request.getTeamId(), operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        if (teamMapper.getActiveMember(request.getTeamId(), request.getUserId()) != null) {
            throw new BusinessException(TeamErrorCode.TEAM_MEMBER_EXISTS.getCode(), "用户已在该团队中");
        }
        if (managementMapper.auditJoinRequest(requestId, JOIN_REQUEST_APPROVED, operatorUserId) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "加入申请状态已变化");
        }
        TeamMember member = new TeamMember();
        member.setTeamId(request.getTeamId());
        member.setUserId(request.getUserId());
        member.setRoleCode(TeamRoleCodes.MEMBER);
        member.setStatus(TeamMemberStatus.ACTIVE);
        member.setJoinTime(LocalDateTime.now());
        teamMapper.upsertMember(member);
        Long conversationId = conversationMapper.getTeamConversationId(request.getTeamId());
        if (conversationId != null) {
            conversationMapper.upsertConversationMember(conversationId, request.getUserId());
        }
        managementMapper.incrementInviteLinkUsedCount(request.getLinkId());
        notificationService.createNotification(
                request.getUserId(),
                SystemNotificationType.TEAM_JOIN_APPROVED,
                "团队申请已通过",
                "你的团队加入申请已通过。",
                SystemNotificationType.TEAM_JOIN_APPROVED,
                requestId,
                request.getTeamId()
        );
        // Remote calls (HTTP + MQ) deferred to afterCommit to avoid holding DB connection
        Long teamId = request.getTeamId();
        Long userId = request.getUserId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                teamPermissionService.grantBuiltInRole(teamId, userId, TeamRoleCodes.MEMBER);
                domainEventPublisher.publish(ImDomainEventType.TEAM_JOIN_REQUEST_APPROVED, Map.of(
                        "teamId", teamId,
                        "operatorUserId", operatorUserId,
                        "userId", userId,
                        "requestId", requestId
                ));
            }
        });
        request.setStatus(JOIN_REQUEST_APPROVED);
        return toJoinRequestVO(request);
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamJoinRequestVO rejectJoinRequest(Long operatorUserId, Long requestId) {
        TeamJoinRequest request = requirePendingJoinRequest(requestId);
        teamPermissionService.requirePermission(request.getTeamId(), operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        if (managementMapper.auditJoinRequest(requestId, JOIN_REQUEST_REJECTED, operatorUserId) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "加入申请状态已变化");
        }
        notificationService.createNotification(
                request.getUserId(),
                SystemNotificationType.TEAM_JOIN_REJECTED,
                "团队申请已拒绝",
                "你的团队加入申请未通过。",
                SystemNotificationType.TEAM_JOIN_REJECTED,
                requestId,
                request.getTeamId()
        );
        // MQ publish deferred to afterCommit to avoid holding DB connection during remote I/O
        Long teamId = request.getTeamId();
        Long userId = request.getUserId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                domainEventPublisher.publish(ImDomainEventType.TEAM_JOIN_REQUEST_REJECTED, Map.of(
                        "teamId", teamId,
                        "operatorUserId", operatorUserId,
                        "userId", userId,
                        "requestId", requestId
                ));
            }
        });
        request.setStatus(JOIN_REQUEST_REJECTED);
        return toJoinRequestVO(request);
    }

    private TeamJoinRequest requirePendingJoinRequest(Long requestId) {
        if (requestId == null || requestId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 不能为空");
        }
        TeamJoinRequest request = managementMapper.getJoinRequestById(requestId);
        if (request == null || !Integer.valueOf(JOIN_REQUEST_PENDING).equals(request.getStatus())) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "加入申请不存在或已处理");
        }
        return request;
    }

    private TeamJoinRequestVO toJoinRequestVO(TeamJoinRequest request) {
        return new TeamJoinRequestVO(request.getId(), request.getTeamId(), request.getUserId(),
                null, null, request.getStatus(), request.getCreateTime());
    }
}
