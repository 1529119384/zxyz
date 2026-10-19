package uno.acloud.team.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.vo.team.TeamMemberDetailVO;
import uno.acloud.team.vo.team.TeamQuotaVO;
import uno.acloud.team.vo.team.TeamVO;

import java.util.List;

/**
 * 内部团队查询服务（65/66 号 🟠-5）：InternalTeamController 的全部业务下沉至此，
 * Controller 只保留 HTTP 边界（照 InternalProjectController Port 范式消解
 * 「Controller 直注 3 Mapper」的分层违规）。
 */
@Service
@RequiredArgsConstructor
public class InternalTeamService {

    private final TeamMapper teamMapper;
    private final TeamQuotaMapper teamQuotaMapper;
    private final TeamEntityMapper teamEntityMapper;

    public TeamVO getTeamById(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        return team == null ? null : teamEntityMapper.toTeamVO(team);
    }

    public List<Long> listTeamIdsByUserId(Long userId) {
        return teamMapper.listMyTeams(userId).stream().map(Team::getId).toList();
    }

    public List<Long> listMemberUserIds(Long teamId) {
        return teamMapper.listMembers(teamId).stream().map(TeamMember::getUserId).toList();
    }

    public TeamMemberDetailVO getActiveMember(Long teamId, Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        return member == null ? null : teamEntityMapper.toMemberDetailVO(member);
    }

    public boolean isActiveMember(Long teamId, Long userId) {
        return teamMapper.getActiveMember(teamId, userId) != null;
    }

    public Long getMemberPersonalStorageLimit(Long teamId, Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        return member == null ? null : member.getPersonalStorageLimit();
    }

    public String getMemberRoleCode(Long teamId, Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        return member == null ? null : member.getRoleCode();
    }

    public TeamQuotaVO getTeamQuota(Long teamId) {
        TeamQuota quota = teamQuotaMapper.getByTeamId(teamId);
        return quota == null ? null : teamEntityMapper.toQuotaVO(quota);
    }

    public int countOccupiedMembers(Long teamId) {
        return teamMapper.countOccupiedMembers(teamId);
    }

    public Long getTeamOwnerId(Long teamId) {
        Team team = teamMapper.selectById(teamId);
        return team == null ? null : team.getOwnerUserId();
    }
}
