package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.common.util.TransactionUtils;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.SystemNotificationType;
import uno.acloud.common.TeamMemberStatus;
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

/**
 * 团队加入申请。
 *
 * <p>60 号审计 🟠-1 整改：本类此前 {@code submitJoinRequest}/{@code approveJoinRequest}/
 * {@code rejectJoinRequest} 整体 {@code @Transactional}，事务内直接发起跨服务 HTTP ——
 * 审核人权限批量 HTTP（{@code listUsersWithPermission}，逐用户最多 3×500ms 重试）、
 * 操作者权限校验（{@code requirePermission}）。HTTP 重试期间事务持续持有 DB 连接。
 * 现按「每方法语义」拆分：</p>
 * <ul>
 *   <li><b>权限校验 HTTP（requirePermission）</b>：提前到事务开启<b>之前</b> ——
 *       校验失败必须让业务写根本不发生，绝不能延迟到 afterCommit；</li>
 *   <li><b>审核人筛选 HTTP（listUsersWithPermission）</b>：afterCommit 延迟；
 *       其后的审核人通知属通知类，逐人 try-catch 容忍失败；</li>
 *   <li><b>角色授予 HTTP（grantBuiltInRole）</b>：afterCommit 延迟 + 人工补偿日志
 *       （沿用 2.2.2 既有注释的取舍：申请已提交为 APPROVED，不可回滚）；</li>
 *   <li>DB 段收窄到自代理事务方法（{@code @Lazy} 自注入，同模块 {@code UserProfileService} 既有模式）。</li>
 * </ul>
 */
@Slf4j
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
    /** 自代理：让 @Transactional 的 DB 段经代理生效。 */
    private final JoinRequestService self;

    public JoinRequestService(TeamMapper teamMapper,
                              TeamManagementMapper managementMapper,
                              ConversationMapper conversationMapper,
                              TeamPermissionService teamPermissionService,
                              SystemNotificationService notificationService,
                              ImDomainEventPublisher domainEventPublisher,
                              @Lazy JoinRequestService self) {
        this.teamMapper = teamMapper;
        this.managementMapper = managementMapper;
        this.conversationMapper = conversationMapper;
        this.teamPermissionService = teamPermissionService;
        this.notificationService = notificationService;
        this.domainEventPublisher = domainEventPublisher;
        // @Lazy 自代理由容器注入；纯 mock 单测传 null 时回退 this —— 事务边界退化为「无事务直调」，
        // 与改前测试行为一致（生产环境 self 恒非 null，DB 段事务由代理保证）。
        this.self = self != null ? self : this;
    }

    public TeamJoinRequestVO submitJoinRequest(Long userId, String token) {
        // DB 校验 + 插入收窄到小事务（HTTP 已全部移出）；已有 PENDING 申请时提前返回（不重复通知，与既有行为一致）
        PendingRequestResult result = self.findOrCreatePendingRequest(userId, token);
        if (!result.created()) {
            return toJoinRequestVO(result.request());
        }
        TeamJoinRequest request = result.request();
        // Remote I/O（审核人权限 HTTP + 通知）deferred to afterCommit（60 号审计 🟠-1）
        TransactionUtils.runAfterCommit("加入申请提交后筛选审核人并发通知 requestId=" + request.getId(),
                () -> notifyReviewers(request.getTeamId(), request.getId()));
        TransactionUtils.runAfterCommit(() -> domainEventPublisher.publish(
                ImDomainEventType.TEAM_JOIN_REQUEST_SUBMITTED, Map.of(
                        "teamId", request.getTeamId(),
                        "userId", userId,
                        "requestId", request.getId(),
                        "linkId", request.getLinkId()
                )));
        return toJoinRequestVO(request);
    }

    /** 新建/已存在的申请及其是否为本次新建；{@code created=false} 时调用方必须提前返回（不重复通知）。 */
    private record PendingRequestResult(TeamJoinRequest request, boolean created) {
    }

    /** DB 段（有事务）：邀请链接校验 + 幂等检查 + 插入；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    PendingRequestResult findOrCreatePendingRequest(Long userId, String token) {
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
            return new PendingRequestResult(existing, false);
        }
        TeamJoinRequest request = new TeamJoinRequest();
        request.setTeamId(link.getTeamId());
        request.setUserId(userId);
        request.setLinkId(link.getId());
        request.setStatus(JOIN_REQUEST_PENDING);
        request.setCreateTime(LocalDateTime.now());
        managementMapper.insertJoinRequest(request);
        return new PendingRequestResult(request, true);
    }

    /**
     * 筛选有审核权限的管理员并发通知（afterCommit 段）。
     * 逐人 try-catch：一个审核人的通知失败不阻断其余审核人（TransactionUtils 对调用方的约定）。
     * 批量 HTTP 的 N+1 结构问题属 🟡-2，本轮按范围克制不改，移交。
     */
    private void notifyReviewers(Long teamId, Long requestId) {
        try {
            List<Long> activeMembers = teamMapper.listActiveMemberUserIds(teamId);
            List<Long> reviewers = teamPermissionService.listUsersWithPermission(
                    teamId, activeMembers, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
            for (Long managerUserId : reviewers) {
                try {
                    notificationService.createNotification(
                            managerUserId,
                            SystemNotificationType.TEAM_JOIN_REQUEST,
                            "新的团队加入申请",
                            "有用户通过邀请链接申请加入团队，请前往协作页审核。",
                            SystemNotificationType.TEAM_JOIN_REQUEST,
                            requestId,
                            teamId
                    );
                } catch (Exception e) {
                    log.warn("审核人通知发送失败（不影响申请结果）: requestId={}, managerUserId={}", requestId, managerUserId, e);
                }
            }
        } catch (Exception e) {
            log.error("加入申请审核人筛选/通知失败（申请已提交为 PENDING，需人工补发通知）: requestId={}, teamId={}",
                    requestId, teamId, e);
        }
    }

    public List<TeamJoinRequestVO> listJoinRequests(Long operatorUserId, Long teamId) {
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        return managementMapper.listPendingJoinRequests(teamId);
    }

    public TeamJoinRequestVO approveJoinRequest(Long operatorUserId, Long requestId) {
        TeamJoinRequest request = requirePendingJoinRequest(requestId);
        // 权限校验 HTTP 必须先于业务写执行（不可延迟到 afterCommit —— 校验失败时业务写根本不该发生）
        teamPermissionService.requirePermission(request.getTeamId(), operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        self.approveInTransaction(request, operatorUserId);
        // Remote I/O（角色授予 HTTP）deferred to afterCommit（60 号审计 🟠-1）
        Long teamId = request.getTeamId();
        Long userId = request.getUserId();
        TransactionUtils.runAfterCommit(() -> {
            // 审计 2.2.2：afterCommit 里抛异常会让接口返回 500，但 join_request 早已提交为 APPROVED
            // ——用户看到「失败」却已被批准，且 MEMBER 角色永久未授予。这里逐个 catch：
            // 授权失败只记日志并保留 requestId 作为人工补偿线索，不再把已提交的事务伪装成失败。
            // （更完整的补偿表/对账方案需结合运维告警渠道落地，已在审计文档中列为待决策项。）
            try {
                teamPermissionService.grantBuiltInRole(teamId, userId, TeamRoleCodes.MEMBER);
            } catch (Exception e) {
                log.error("加入申请已通过但授予 MEMBER 角色失败（需人工补偿）: teamId={}, userId={}, requestId={}",
                        teamId, userId, requestId, e);
            }
            try {
                domainEventPublisher.publish(ImDomainEventType.TEAM_JOIN_REQUEST_APPROVED, Map.of(
                        "teamId", teamId,
                        "operatorUserId", operatorUserId,
                        "userId", userId,
                        "requestId", requestId
                ));
            } catch (Exception e) {
                log.error("发布加入申请通过事件失败（事务已提交，不可回滚）: requestId={}", requestId, e);
            }
        });
        request.setStatus(JOIN_REQUEST_APPROVED);
        return toJoinRequestVO(request);
    }

    /** DB 段（有事务）：成员关系/会话/邀请链接计数/申请人通知；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    void approveInTransaction(TeamJoinRequest request, Long operatorUserId) {
        if (teamMapper.getActiveMember(request.getTeamId(), request.getUserId()) != null) {
            throw new BusinessException(TeamErrorCode.TEAM_MEMBER_EXISTS.getCode(), "用户已在该团队中");
        }
        if (managementMapper.auditJoinRequest(request.getId(), JOIN_REQUEST_APPROVED, operatorUserId) != 1) {
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
        // 65/66 号二.4：超卖守卫 —— WHERE 已带 used_count < max_uses 上限条件，
        // 0 行更新 = 链接次数已用尽（并发耗尽场景），拒绝本次通过而不是静默超发。
        int incremented = managementMapper.incrementInviteLinkUsedCount(request.getLinkId());
        if (incremented == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邀请链接使用次数已达上限");
        }
        notificationService.createNotification(
                request.getUserId(),
                SystemNotificationType.TEAM_JOIN_APPROVED,
                "团队申请已通过",
                "你的团队加入申请已通过。",
                SystemNotificationType.TEAM_JOIN_APPROVED,
                request.getId(),
                request.getTeamId()
        );
    }

    public TeamJoinRequestVO rejectJoinRequest(Long operatorUserId, Long requestId) {
        TeamJoinRequest request = requirePendingJoinRequest(requestId);
        // 权限校验 HTTP 先于业务写（同 approveJoinRequest 的语义决策）
        teamPermissionService.requirePermission(request.getTeamId(), operatorUserId, TeamPermissionCodes.TEAM_JOIN_REQUEST_REVIEW);
        self.rejectInTransaction(request, operatorUserId);
        TransactionUtils.runAfterCommit(() -> domainEventPublisher.publish(
                ImDomainEventType.TEAM_JOIN_REQUEST_REJECTED, Map.of(
                        "teamId", request.getTeamId(),
                        "operatorUserId", operatorUserId,
                        "userId", request.getUserId(),
                        "requestId", request.getId()
                )));
        request.setStatus(JOIN_REQUEST_REJECTED);
        return toJoinRequestVO(request);
    }

    /** DB 段（有事务）：拒绝更新 + 申请人通知；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    void rejectInTransaction(TeamJoinRequest request, Long operatorUserId) {
        if (managementMapper.auditJoinRequest(request.getId(), JOIN_REQUEST_REJECTED, operatorUserId) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_INVITATION_INVALID.getCode(), "加入申请状态已变化");
        }
        notificationService.createNotification(
                request.getUserId(),
                SystemNotificationType.TEAM_JOIN_REJECTED,
                "团队申请已拒绝",
                "你的团队加入申请未通过。",
                SystemNotificationType.TEAM_JOIN_REJECTED,
                request.getId(),
                request.getTeamId()
        );
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
