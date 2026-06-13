package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.enums.ConversationMemberStatus;
import uno.acloud.im.domain.enums.ConversationType;
import uno.acloud.im.domain.enums.TeamMemberStatus;
import uno.acloud.im.domain.enums.TeamStatus;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.domain.model.Team;
import uno.acloud.im.domain.model.TeamMember;
import uno.acloud.im.infrastructure.mapper.ConversationMapper;
import uno.acloud.im.infrastructure.mapper.TeamMapper;
import uno.acloud.im.dto.CreateTeamRequest;
import uno.acloud.im.dto.UpdateTeamRequest;
import uno.acloud.im.vo.TeamMemberVO;
import uno.acloud.im.vo.TeamVO;

import java.time.LocalDateTime;
import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class TeamService {

    private final TeamMapper teamMapper;
    private final ConversationMapper conversationMapper;
    private final UserProfileService userProfileService;
    private final TeamPermissionService teamPermissionService;

    public TeamService(TeamMapper teamMapper,
                       ConversationMapper conversationMapper,
                       UserProfileService userProfileService,
                       TeamPermissionService teamPermissionService) {
        this.teamMapper = teamMapper;
        this.conversationMapper = conversationMapper;
        this.userProfileService = userProfileService;
        this.teamPermissionService = teamPermissionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamVO createTeam(Long ownerUserId, CreateTeamRequest request) {
        String name = normalizeTeamName(request);
        userProfileService.ensurePlaceholder(ownerUserId);

        LocalDateTime now = LocalDateTime.now();
        Team team = new Team();
        team.setName(name);
        team.setAvatar(optionalText(request == null ? null : request.getAvatar(), 512, "头像地址长度不能超过 512"));
        team.setDescription(optionalText(request == null ? null : request.getDescription(), 500, "团队描述长度不能超过 500"));
        team.setOwnerUserId(ownerUserId);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreateTime(now);
        team.setUpdateTime(now);
        teamMapper.insertTeam(team);

        TeamMember owner = new TeamMember();
        owner.setTeamId(team.getId());
        owner.setUserId(ownerUserId);
        owner.setRoleCode(TeamRoleCodes.OWNER);
        owner.setStatus(TeamMemberStatus.ACTIVE);
        owner.setJoinTime(now);
        teamMapper.upsertMember(owner);
        teamPermissionService.initializeBuiltInRoles(team.getId(), ownerUserId);

        ImConversation conversation = new ImConversation();
        conversation.setType(ConversationType.TEAM);
        conversation.setTeamId(team.getId());
        conversation.setBizKey("TEAM:" + team.getId());
        conversation.setDirectUserA(null);
        conversation.setDirectUserB(null);
        conversation.setStatus(ConversationMemberStatus.ACTIVE);
        conversation.setCreateTime(now);
        conversation.setUpdateTime(now);
        conversationMapper.insertConversation(conversation);
        conversationMapper.upsertConversationMember(conversation.getId(), ownerUserId);

        return toTeamVO(team, TeamRoleCodes.OWNER, teamPermissionService.listMemberPermissions(team.getId(), ownerUserId));
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamVO updateTeam(Long operatorUserId, Long teamId, UpdateTeamRequest request) {
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_UPDATE);
        Team team = teamMapper.getTeamById(teamId);
        if (team == null) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队不存在");
        }
        team.setName(normalizeTeamName(request == null ? null : request.getName()));
        team.setAvatar(optionalText(request == null ? null : request.getAvatar(), 512, "头像地址长度不能超过 512"));
        team.setDescription(optionalText(request == null ? null : request.getDescription(), 500, "团队描述长度不能超过 500"));
        if (teamMapper.updateTeamProfile(team) != 1) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队不存在");
        }
        return toTeamVO(team, teamPermissionService.getMemberRoleCode(teamId, operatorUserId).orElse(null),
                teamPermissionService.listMemberPermissions(teamId, operatorUserId));
    }

    public List<TeamVO> listMyTeams(Long userId) {
        return teamMapper.listMyTeams(userId).stream()
                .map(team -> new TeamVO(team.getId(), team.getName(), team.getAvatar(), team.getDescription(),
                        team.getOwnerUserId(), team.getMyRoleCode(),
                        teamPermissionService.listMemberPermissions(team.getId(), userId), team.getCreateTime()))
                .toList();
    }

    public List<TeamMemberVO> listMembers(Long userId, Long teamId) {
        teamPermissionService.requirePermission(teamId, userId, TeamPermissionCodes.TEAM_MEMBER_VIEW);
        return teamMapper.listMembers(teamId);
    }

    public TeamMember requireActiveMember(Long teamId, Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        if (member == null) {
            throw new BusinessException(ErrorCode.TEAM_NOT_FOUND, "团队不存在或你不在该团队中");
        }
        return member;
    }

    public void requireManager(Long teamId, Long userId) {
        teamPermissionService.requirePermission(teamId, userId, TeamPermissionCodes.TEAM_UPDATE);
    }

    private String normalizeTeamName(CreateTeamRequest request) {
        String name = request == null ? "" : request.getName();
        return normalizeTeamName(name);
    }

    private String normalizeTeamName(String rawName) {
        return requireText(rawName, "团队名称不能为空", 50, "团队名称长度不能超过 50");
    }

    private TeamVO toTeamVO(Team team, String myRoleCode, List<String> myPermissions) {
        return new TeamVO(team.getId(), team.getName(), team.getAvatar(), team.getDescription(), team.getOwnerUserId(), myRoleCode, myPermissions, team.getCreateTime());
    }
}
