package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.enums.TeamMemberStatus;
import uno.acloud.im.domain.enums.TeamStatus;
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

@Slf4j
@Service
public class InternalTeamSyncService {

    private final TeamMapper teamMapper;
    private final ConversationMapper conversationMapper;
    private final UserProfileMapper userProfileMapper;
    private final TeamPermissionService teamPermissionService;

    public InternalTeamSyncService(TeamMapper teamMapper,
                                   ConversationMapper conversationMapper,
                                   UserProfileMapper userProfileMapper,
                                   TeamPermissionService teamPermissionService) {
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.userProfileMapper = userProfileMapper;
        this.teamPermissionService = teamPermissionService;
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

    @Transactional(rollbackFor = Exception.class)
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
        teamMapper.deactivateMember(request.getTeamId(), request.getUserId());
        teamPermissionService.clearMemberRole(request.getTeamId(), request.getUserId());
        conversationMapper.deactivateUserConversationsInTeam(request.getTeamId(), request.getUserId());
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
