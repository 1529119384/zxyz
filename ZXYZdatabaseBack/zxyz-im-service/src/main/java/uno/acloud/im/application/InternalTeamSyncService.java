package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.common.util.TransactionUtils;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.common.TeamMemberStatus;
import uno.acloud.common.TeamStatus;
import uno.acloud.im.infrastructure.persistence.entity.ImConversation;
import uno.acloud.im.infrastructure.persistence.entity.Team;
import uno.acloud.im.infrastructure.persistence.entity.TeamMember;
import uno.acloud.im.infrastructure.persistence.entity.UserProfile;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.infrastructure.mapper.UserProfileMapper;
import uno.acloud.im.dto.InternalTeamMemberRemovalRequest;
import uno.acloud.im.dto.InternalTeamMemberSyncRequest;
import uno.acloud.im.dto.InternalTeamSyncRequest;

import java.time.LocalDateTime;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

/**
 * 团队 MQ 同步（team.created / team.member.* 事件的消费侧落库）。
 *
 * <p>60 号审计 🟠-1 整改：{@code removeMember} 此前在 {@code @Transactional} 内直接调
 * {@code clearMemberRole}（跨服务写 HTTP），消费线程的重试期间占着 DB 连接。现把 DB 段
 * 收窄到自代理事务方法，角色清除 HTTP 延迟到 afterCommit + 人工补偿日志（消费端本就有
 * 幂等键释放 + MQ 重投，此处 afterCommit 失败的最坏情形与改前一致：team_member_role 残留，
 * 由 ERROR 日志暴露）。</p>
 */
@Slf4j
@Service
public class InternalTeamSyncService {

    private final TeamMapper teamMapper;
    private final ConversationMapper conversationMapper;
    private final UserProfileMapper userProfileMapper;
    private final TeamPermissionService teamPermissionService;
    /** 自代理：让 @Transactional 的 DB 段经代理生效。 */
    private final InternalTeamSyncService self;

    public InternalTeamSyncService(TeamMapper teamMapper,
                                   ConversationMapper conversationMapper,
                                   UserProfileMapper userProfileMapper,
                                   TeamPermissionService teamPermissionService,
                                   @Lazy InternalTeamSyncService self) {
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.userProfileMapper = userProfileMapper;
        this.teamPermissionService = teamPermissionService;
        // @Lazy 自代理由容器注入；纯 mock 单测传 null 时回退 this（事务边界退化为无事务直调）。
        this.self = self != null ? self : this;
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncTeam(InternalTeamSyncRequest request) {
        if (request == null || request.getTeamId() == null || request.getOwnerUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "团队同步参数不完整");
        }
        // m50: 幂等性检查 — 团队已存在则跳过，避免重复插入
        Team existing = teamMapper.getTeamById(request.getTeamId());
        if (existing != null) {
            log.debug("MQ幂等: 团队已存在，跳过同步: teamId={}", request.getTeamId());
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Team team = new Team();
        team.setId(request.getTeamId());
        team.setName(requireText(request.getName(), "团队名称不能为空"));
        team.setAvatar(optionalText(request.getAvatar()));
        team.setDescription(optionalText(request.getDescription(), 500, "团队描述长度不能超过 500"));
        team.setOwnerUserId(request.getOwnerUserId());
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        teamMapper.upsertTeamWithId(team);

        upsertProfile(request.getOwnerUserId(), request.getOwnerUsername(), request.getOwnerName(), null);
        upsertMember(request.getTeamId(), request.getOwnerUserId(), TeamRoleCodes.OWNER);
        // 权限初始化已由 Team Service 在创建团队时处理，IM Service 不再重复调用
        ensureTeamConversation(request.getTeamId(), request.getOwnerUserId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncTeamProfile(InternalTeamSyncRequest request) {
        if (request == null || request.getTeamId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "团队资料同步参数不完整");
        }
        Team team = teamMapper.getTeamById(request.getTeamId());
        if (team == null) {
            throw new BusinessException(TeamErrorCode.TEAM_NOT_FOUND.getCode(), "IM 团队投影不存在");
        }
        team.setName(requireText(request.getName(), "团队名称不能为空"));
        team.setAvatar(optionalText(request.getAvatar()));
        team.setDescription(optionalText(request.getDescription(), 500, "团队描述长度不能超过 500"));
        if (teamMapper.updateTeamProfile(team) != 1) {
            throw new BusinessException(TeamErrorCode.TEAM_NOT_FOUND.getCode(), "IM 团队投影不存在");
        }
        if (request.getOwnerUserId() != null) {
            upsertProfile(request.getOwnerUserId(), request.getOwnerUsername(), request.getOwnerName(), null);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void syncMember(InternalTeamMemberSyncRequest request) {
        if (request == null || request.getTeamId() == null || request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员同步参数不完整");
        }
        // m50: 幂等性检查 — 成员已存在且活跃则跳过，避免重复处理
        TeamMember existingMember = teamMapper.getActiveMember(request.getTeamId(), request.getUserId());
        if (existingMember != null) {
            log.debug("MQ幂等: 成员已存在且活跃，跳过同步: teamId={}, userId={}", request.getTeamId(), request.getUserId());
            return;
        }
        String roleCode = StringUtils.hasText(request.getRoleCode()) ? request.getRoleCode() : TeamRoleCodes.MEMBER;
        upsertProfile(request.getUserId(), request.getUsername(), request.getName(), request.getAvatar());
        upsertMember(request.getTeamId(), request.getUserId(), roleCode);
        // 角色分配已由 Team Service 在添加成员时处理，IM Service 不再重复调用
        Long conversationId = conversationMapper.getTeamConversationId(request.getTeamId());
        if (conversationId == null) {
            ensureTeamConversation(request.getTeamId(), request.getUserId());
        } else {
            conversationMapper.upsertConversationMember(conversationId, request.getUserId());
        }
    }

    public void removeMember(InternalTeamMemberRemovalRequest request) {
        if (request == null || request.getTeamId() == null || request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "成员移除同步参数不完整");
        }
        // m50: 幂等性检查 — 成员已不存在或已移除则跳过，避免重复处理
        TeamMember existingMember = teamMapper.getActiveMember(request.getTeamId(), request.getUserId());
        if (existingMember == null) {
            log.debug("MQ幂等: 成员已不存在或已移除，跳过移除: teamId={}, userId={}", request.getTeamId(), request.getUserId());
            return;
        }
        self.removeMemberInTransaction(request.getTeamId(), request.getUserId());
        // Remote I/O（角色清除 HTTP）deferred to afterCommit（60 号审计 🟠-1）
        TransactionUtils.runAfterCommit("成员移除同步后清除角色 teamId=" + request.getTeamId() + ", userId=" + request.getUserId(),
                () -> {
                    try {
                        teamPermissionService.clearMemberRole(request.getTeamId(), request.getUserId());
                    } catch (Exception e) {
                        log.error("成员已移除但清除 team-service 角色失败（需人工补偿）: teamId={}, userId={}",
                                request.getTeamId(), request.getUserId(), e);
                    }
                });
    }

    /** DB 段（有事务）：成员行 + 会话可见性；无任何远程调用。 */
    @Transactional(rollbackFor = Exception.class)
    void removeMemberInTransaction(Long teamId, Long userId) {
        teamMapper.deactivateMember(teamId, userId);
        conversationMapper.deactivateUserConversationsInTeam(teamId, userId);
    }

    private void ensureTeamConversation(Long teamId, Long ownerUserId) {
        Long existingConversationId = conversationMapper.getTeamConversationId(teamId);
        if (existingConversationId != null) {
            conversationMapper.upsertConversationMember(existingConversationId, ownerUserId);
            return;
        }
        ImConversation conversation = new ImConversation();
        conversation.setType(ConversationType.TEAM);
        conversation.setTeamId(teamId);
        conversation.setBizKey("TEAM:" + teamId);
        conversation.markActive();
        conversationMapper.insertConversation(conversation);
        conversationMapper.upsertConversationMember(conversation.getId(), ownerUserId);
    }

    private void upsertMember(Long teamId, Long userId, String roleCode) {
        TeamMember member = new TeamMember();
        member.setTeamId(teamId);
        member.setUserId(userId);
        member.setRoleCode(roleCode);
        member.setStatus(TeamMemberStatus.ACTIVE);
        member.setJoinTime(LocalDateTime.now());
        teamMapper.upsertMember(member);
    }

    private void upsertProfile(Long userId, String username, String name, String avatar) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setUsername(StringUtils.hasText(username) ? username : "user-" + userId);
        profile.setName(optionalText(name));
        profile.setAvatar(optionalText(avatar));
        userProfileMapper.upsert(profile);
    }
}
