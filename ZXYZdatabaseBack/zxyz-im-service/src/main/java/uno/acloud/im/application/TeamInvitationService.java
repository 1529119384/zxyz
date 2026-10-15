package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.common.util.TransactionUtils;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.enums.InvitationStatus;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.common.TeamMemberStatus;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.TeamInvitation;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.convert.ImEntityMapper;
import uno.acloud.im.infrastructure.mapper.TeamInvitationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.dto.InviteUserRequest;
import uno.acloud.im.vo.TeamInvitationVO;

import java.time.LocalDateTime;

/**
 * 团队邀请。
 *
 * <p>60 号审计 🟠-1 整改（58 号 §二.18 同源收口）：{@code invite} 此前在事务内直接发起
 * 跨服务 HTTP（{@code requirePermission} 校验、{@code ensurePlaceholder} 触发的用户搜索/同步）；
 * {@code accept} 在事务内调 {@code grantBuiltInRole} 写 HTTP。现按「每方法语义」拆分：</p>
 * <ul>
 *   <li><b>权限校验 HTTP（invite 的 requirePermission）</b>：提前到事务开启<b>之前</b> ——
 *       校验失败必须让邀请根本不发生；</li>
 *   <li><b>占位档案同步 HTTP（ensurePlaceholder）</b>：提前到事务开启之前（失败即邀请失败，
 *       语义不变、时机前移）；</li>
 *   <li><b>角色授予 HTTP（accept 的 grantBuiltInRole）</b>：afterCommit 延迟 + 人工补偿日志
 *       （邀请已提交为 ACCEPTED，成员行已落库，不可回滚；失败留 teamId/userId/invitationId 线索）；</li>
 *   <li><b>领域事件（进程内 ApplicationEvent）</b>：随调用点各移至 afterCommit，
 *       与 {@code JoinRequestService} 的既有口径对齐。</li>
 * </ul>
 */
@Slf4j
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
    /** 自代理：让 @Transactional 的 DB 段经代理生效。 */
    private final TeamInvitationService self;

    public TeamInvitationService(TeamService teamService,
                                 UserProfileService userProfileService,
                                 TeamMapper teamMapper,
                                 TeamInvitationMapper invitationMapper,
                                 ConversationMapper conversationMapper,
                                 SystemNotificationService systemNotificationService,
                                 ImDomainEventPublisher domainEventPublisher,
                                 TeamPermissionService teamPermissionService,
                                 ImEntityMapper imEntityMapper,
                                 @Lazy TeamInvitationService self) {
        this.teamService = teamService;
        this.userProfileService = userProfileService;
        this.teamMapper = teamMapper;
        this.invitationMapper = invitationMapper;
        this.conversationMapper = conversationMapper;
        this.systemNotificationService = systemNotificationService;
        this.domainEventPublisher = domainEventPublisher;
        this.teamPermissionService = teamPermissionService;
        this.imEntityMapper = imEntityMapper;
        // @Lazy 自代理由容器注入；纯 mock 单测传 null 时回退 this（事务边界退化为无事务直调）。
        this.self = self != null ? self : this;
    }

    public TeamInvitationVO invite(Long inviterUserId, Long teamId, InviteUserRequest request) {
        Long inviteeUserId = normalizeInviteeUserId(request);
        if (inviterUserId.equals(inviteeUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能邀请自己");
        }

        // 权限校验 HTTP + 占位档案 HTTP 必须先于业务写执行（校验失败时邀请根本不该发生）；
        // 占位档案属「业务前置条件」，延迟到 afterCommit 会让邀请指向不存在的档案（语义破坏）。
        teamPermissionService.requirePermission(teamId, inviterUserId, TeamPermissionCodes.TEAM_MEMBER_INVITE);
        userProfileService.ensurePlaceholder(inviteeUserId);

        TeamInvitation invitation = self.createInvitationInTransaction(teamId, inviterUserId, inviteeUserId);
        // 领域事件 deferred to afterCommit（60 号审计 🟠-1，与 JoinRequestService 口径对齐）
        TransactionUtils.runAfterCommit(() -> domainEventPublisher.publish(ImDomainEventType.TEAM_INVITATION_CREATED, java.util.Map.of(
                "teamId", teamId,
                "inviterUserId", inviterUserId,
                "inviteeUserId", inviteeUserId,
                "invitationId", invitation.getId()
        )));
        return imEntityMapper.toInvitationVO(invitation);
    }

    /** DB 段（有事务）：幂等检查 + 插入 + 邀请通知；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    TeamInvitation createInvitationInTransaction(Long teamId, Long inviterUserId, Long inviteeUserId) {
        if (teamMapper.getActiveMember(teamId, inviteeUserId) != null) {
            throw new BusinessException(TeamErrorCode.TEAM_MEMBER_EXISTS.getCode(), "用户已在该团队中");
        }

        TeamInvitation existing = invitationMapper.getPendingByTeamAndInvitee(teamId, inviteeUserId);
        if (existing != null) {
            return existing;
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
        return invitation;
    }

    public TeamInvitationVO accept(Long userId, Long invitationId) {
        TeamInvitation invitation = requirePendingInvitation(userId, invitationId);
        self.acceptInTransaction(invitation, userId);
        // Remote I/O（角色授予 HTTP）deferred to afterCommit（60 号审计 🟠-1）
        Long teamId = invitation.getTeamId();
        TransactionUtils.runAfterCommit("邀请接受后授予 MEMBER 角色 invitationId=" + invitationId, () -> {
            try {
                teamPermissionService.grantBuiltInRole(teamId, userId, TeamRoleCodes.MEMBER);
            } catch (Exception e) {
                log.error("邀请已接受但授予 MEMBER 角色失败（需人工补偿）: teamId={}, userId={}, invitationId={}",
                        teamId, userId, invitationId, e);
            }
        });
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setHandleTime(LocalDateTime.now());
        return imEntityMapper.toInvitationVO(invitation);
    }

    /** DB 段（有事务）：状态推进 + 成员行 + 会话成员 + 已读标记；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    void acceptInTransaction(TeamInvitation invitation, Long userId) {
        if (invitationMapper.updateStatus(invitation.getId(), InvitationStatus.ACCEPTED) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "邀请状态已变化");
        }

        TeamMember member = new TeamMember();
        member.setTeamId(invitation.getTeamId());
        member.setUserId(userId);
        member.setRoleCode(TeamRoleCodes.MEMBER);
        member.setStatus(TeamMemberStatus.ACTIVE);
        member.setJoinTime(LocalDateTime.now());
        teamMapper.upsertMember(member);

        Long conversationId = conversationMapper.getTeamConversationId(invitation.getTeamId());
        if (conversationId == null) {
            conversationId = createMissingTeamConversation(invitation.getTeamId());
        }
        conversationMapper.upsertConversationMember(conversationId, userId);
        systemNotificationService.markBusinessRead(userId, BUSINESS_TYPE_INVITATION, invitation.getId());
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
        if (invitation.getExpireTime() != null && invitation.getExpireTime().isBefore(LocalDateTime.now())) {
            invitationMapper.updateStatus(invitationId, InvitationStatus.EXPIRED);
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_EXPIRED.getCode(), "邀请已过期");
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
        ImConversation conversation = new ImConversation();
        conversation.setType(ConversationType.TEAM);
        conversation.setTeamId(teamId);
        conversation.setBizKey("TEAM:" + teamId);
        conversation.markActive();
        conversationMapper.insertConversation(conversation);
        return conversation.getId();
    }

}
