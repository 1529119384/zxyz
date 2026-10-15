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
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.im.domain.event.ImDomainEventType;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;

import java.util.Map;

/**
 * 团队生命周期（退群/移群）。
 *
 * <p>60 号审计 🟠-1 整改：本类此前整体 {@code @Transactional}，方法内直接发起跨服务 HTTP
 * （{@code clearMemberRole} 写调用、{@code notifyManagers} 逐成员权限 HTTP），
 * HTTP 重试（最多 3×500ms）期间事务持续持有 DB 连接。现拆分为：</p>
 * <ul>
 *   <li><b>权限校验 HTTP（removeMember 的 requirePermission）</b>：提前到事务开启<b>之前</b>
 *       —— 校验失败必须让业务写根本不发生，绝不能延迟到 afterCommit；</li>
 *   <li><b>角色清除 HTTP（clearMemberRole）</b>：afterCommit 延迟 + try-catch +
 *       人工补偿日志（与 {@code JoinRequestService.approveJoinRequest} 的既有范式一致：
 *       事务已提交不可回滚，失败留主键线索交人工对账）；</li>
 *   <li><b>通知类（notifyManagers）</b>：afterCommit 延迟，逐成员 try-catch ——
 *       单个成员的权限 HTTP 失败不阻断其余成员的通知（TransactionUtils 对调用方的约定）。</li>
 * </ul>
 *
 * <p>DB 读守卫（requireActiveMember / 最后管理员检查）留在事务边界外执行亦可：
 * 真正的并发闸门是条件 UPDATE（deactivateMember），与改前同水位。</p>
 */
@Slf4j
@Service
public class TeamLifecycleService {

    private final TeamService teamService;
    private final TeamMapper teamMapper;
    private final ConversationMapper conversationMapper;
    private final SystemNotificationService notificationService;
    private final ImDomainEventPublisher domainEventPublisher;
    private final TeamPermissionService teamPermissionService;
    /** 自代理：让 @Transactional 的 DB 段经代理生效（同 UserProfileService 的既有模式）。 */
    private final TeamLifecycleService self;

    public TeamLifecycleService(TeamService teamService,
                                TeamMapper teamMapper,
                                ConversationMapper conversationMapper,
                                SystemNotificationService notificationService,
                                ImDomainEventPublisher domainEventPublisher,
                                TeamPermissionService teamPermissionService,
                                @Lazy TeamLifecycleService self) {
        this.teamService = teamService;
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.notificationService = notificationService;
        this.domainEventPublisher = domainEventPublisher;
        this.teamPermissionService = teamPermissionService;
        // @Lazy 自代理由容器注入；纯 mock 单测传 null 时回退 this（事务边界退化为无事务直调）。
        this.self = self != null ? self : this;
    }

    public void leaveTeam(Long userId, Long teamId) {
        TeamMember member = teamService.requireActiveMember(teamId, userId);
        if (TeamRoleCodes.OWNER.equals(member.getRoleCode()) && teamMapper.countActiveOwners(teamId) <= 1) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "最后一个大管理员不能直接退出团队");
        }
        self.deactivateAccessInTransaction(teamId, userId);
        // Remote I/O deferred to afterCommit（60 号审计 🟠-1）：事务已提交，HTTP 失败只记日志
        TransactionUtils.runAfterCommit("成员退群后清理角色与通知 teamId=" + teamId + ", userId=" + userId, () -> {
            clearMemberRoleQuietly(teamId, userId);
            notifyManagers(teamId, SystemNotificationType.TEAM_MEMBER_LEFT, "成员已退出团队", "有成员退出了团队。", userId.longValue());
        });
        // 领域事件同样收口 runAfterCommit（与 JoinRequestService 口径一致）：无外层事务时同步执行，
        // 有外层事务时等待提交后再发，避免事件早于业务数据可见。
        TransactionUtils.runAfterCommit(() -> domainEventPublisher.publish(ImDomainEventType.TEAM_MEMBER_LEFT, Map.of(
                "teamId", teamId,
                "userId", userId
        )));
    }

    public void removeMember(Long operatorUserId, Long teamId, Long targetUserId) {
        if (operatorUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能移除自己，请使用退出团队");
        }
        // 权限校验 HTTP 必须先于业务写执行（不可延迟到 afterCommit —— 校验失败时业务写根本不该发生）
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);
        TeamMember target = teamService.requireActiveMember(teamId, targetUserId);
        self.deactivateAccessInTransaction(teamId, targetUserId);
        // Remote I/O deferred to afterCommit（60 号审计 🟠-1）
        TransactionUtils.runAfterCommit("成员移除后清理角色与通知 teamId=" + teamId + ", userId=" + targetUserId, () -> {
            clearMemberRoleQuietly(teamId, targetUserId);
            try {
                notificationService.createNotification(
                        targetUserId,
                        SystemNotificationType.TEAM_MEMBER_REMOVED,
                        "你已被移出团队",
                        "管理员已将你移出团队，相关团队会话不再可见。",
                        SystemNotificationType.TEAM_MEMBER_REMOVED,
                        teamId,
                        teamId
                );
            } catch (Exception e) {
                logNotificationFailure("被移除成员通知", teamId, targetUserId, e);
            }
            notifyManagers(teamId, SystemNotificationType.TEAM_MEMBER_REMOVED, "成员已被移除", "有成员被移出了团队。", targetUserId.longValue());
        });
        // 领域事件同样收口 runAfterCommit（与 JoinRequestService 口径一致）
        TransactionUtils.runAfterCommit(() -> domainEventPublisher.publish(ImDomainEventType.TEAM_MEMBER_REMOVED, Map.of(
                "teamId", teamId,
                "operatorUserId", operatorUserId,
                "targetUserId", targetUserId
        )));
    }

    /** DB 段（有事务）：只做本地写；跨服务 HTTP 一律不放这里。 */
    @Transactional(rollbackFor = Exception.class)
    void deactivateAccessInTransaction(Long teamId, Long userId) {
        teamMapper.deactivateMember(teamId, userId);
        conversationMapper.deactivateUserConversationsInTeam(teamId, userId);
    }

    /**
     * 角色清除（跨服务写）—— 事务已提交后的尽力而为：失败只记 ERROR 并保留主键
     * （teamId/userId）作人工补偿线索，不把已成功的退群/移群伪装成失败。
     */
    private void clearMemberRoleQuietly(Long teamId, Long userId) {
        try {
            teamPermissionService.clearMemberRole(teamId, userId);
        } catch (Exception e) {
            log.error("成员已退出/移除但清除 team-service 角色失败（需人工补偿）: teamId={}, userId={}", teamId, userId, e);
        }
    }

    /**
     * 通知各管理员。逐成员 try-catch：一个成员的权限 HTTP / 通知写失败不阻断其余成员
     * （N+1 结构问题属 🟡-1，本轮按范围克制不改，移交）。
     */
    private void notifyManagers(Long teamId, String type, String title, String content, Long businessId) {
        for (Long memberUserId : teamMapper.listActiveMemberUserIds(teamId)) {
            try {
                TeamMember member = teamMapper.getActiveMember(teamId, memberUserId);
                if (member != null && teamPermissionService.hasPermission(teamId, memberUserId, TeamPermissionCodes.TEAM_MEMBER_REMOVE)) {
                    notificationService.createNotification(memberUserId, type, title, content, type, businessId, teamId);
                }
            } catch (Exception e) {
                logNotificationFailure("管理员通知", teamId, memberUserId, e);
            }
        }
    }

    private void logNotificationFailure(String scene, Long teamId, Long userId, Exception e) {
        log.warn("{}发送失败（不影响业务结果）: teamId={}, userId={}", scene, teamId, userId, e);
    }
}
