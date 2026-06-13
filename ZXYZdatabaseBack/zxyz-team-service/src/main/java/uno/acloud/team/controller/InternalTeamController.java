package uno.acloud.team.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.team.entity.Team;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamQuota;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.vo.team.TeamMemberDetailVO;
import uno.acloud.team.vo.team.TeamQuotaVO;
import uno.acloud.team.vo.team.TeamVO;

import java.util.List;

@Hidden
@Tag(name = "团队管理（内部）", description = "内部服务团队查询 API")
@RestController
@RequestMapping("/api/internal/teams")
public class InternalTeamController {

    private final TeamMapper teamMapper;
    private final TeamQuotaMapper teamQuotaMapper;
    private final TeamEntityMapper teamEntityMapper;

    public InternalTeamController(TeamMapper teamMapper, TeamQuotaMapper teamQuotaMapper, TeamEntityMapper teamEntityMapper) {
        this.teamMapper = teamMapper;
        this.teamQuotaMapper = teamQuotaMapper;
        this.teamEntityMapper = teamEntityMapper;
    }

    @Operation(summary = "根据ID查询团队")
    @GetMapping("/{teamId}")
    @SuppressWarnings("unchecked")
    public Result<TeamVO> getTeamById(@PathVariable Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null) {
            return (Result<TeamVO>) (Result<?>) Result.success();
        }
        return Result.of(teamEntityMapper.toTeamVO(team));
    }

    @Operation(summary = "查询用户所属团队列表")
    @GetMapping("/by-user/{userId}")
    public Result<List<TeamVO>> listTeamsByUserId(@PathVariable Long userId) {
        List<Team> teams = teamMapper.listMyTeams(userId);
        return Result.of(teamEntityMapper.toTeamVOList(teams));
    }

    @Operation(summary = "查询团队成员用户ID列表")
    @GetMapping("/{teamId}/members")
    public Result<List<Long>> listMemberUserIds(@PathVariable Long teamId) {
        List<TeamMember> members = teamMapper.listMembers(teamId);
        return Result.of(members.stream().map(TeamMember::getUserId).toList());
    }

    @Operation(summary = "查询团队活跃成员详情")
    @GetMapping("/{teamId}/members/{userId}")
    @SuppressWarnings("unchecked")
    public Result<TeamMemberDetailVO> getActiveMember(@PathVariable Long teamId, @PathVariable Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        if (member == null) {
            return (Result<TeamMemberDetailVO>) (Result<?>) Result.success();
        }
        return Result.of(teamEntityMapper.toMemberDetailVO(member));
    }

    @Operation(summary = "检查是否为团队活跃成员")
    @GetMapping("/{teamId}/members/{userId}/active")
    public Result<Boolean> isActiveMember(@PathVariable Long teamId, @PathVariable Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        return Result.of(member != null);
    }

    @Operation(summary = "查询成员个人存储配额")
    @GetMapping("/{teamId}/members/{userId}/storage-limit")
    @SuppressWarnings("unchecked")
    public Result<Long> getMemberPersonalStorageLimit(@PathVariable Long teamId, @PathVariable Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        if (member == null) {
            return (Result<Long>) (Result<?>) Result.success();
        }
        return Result.of(member.getPersonalStorageLimit());
    }

    @Operation(summary = "查询成员角色编码")
    @GetMapping("/{teamId}/members/{userId}/role")
    @SuppressWarnings("unchecked")
    public Result<String> getMemberRoleCode(@PathVariable Long teamId, @PathVariable Long userId) {
        TeamMember member = teamMapper.getActiveMember(teamId, userId);
        if (member == null) {
            return (Result<String>) (Result<?>) Result.success();
        }
        return Result.of(member.getRoleCode());
    }

    @Operation(summary = "查询团队配额")
    @GetMapping("/{teamId}/quota")
    @SuppressWarnings("unchecked")
    public Result<TeamQuotaVO> getTeamQuota(@PathVariable Long teamId) {
        TeamQuota quota = teamQuotaMapper.getByTeamId(teamId);
        if (quota == null) {
            return (Result<TeamQuotaVO>) (Result<?>) Result.success();
        }
        return Result.of(teamEntityMapper.toQuotaVO(quota));
    }

    @Operation(summary = "统计团队成员数量")
    @GetMapping("/{teamId}/member-count")
    public Result<Integer> countOccupiedMembers(@PathVariable Long teamId) {
        return Result.of(teamMapper.countOccupiedMembers(teamId));
    }

    @Operation(summary = "查询团队所有者ID")
    @GetMapping("/{teamId}/owner-id")
    @SuppressWarnings("unchecked")
    public Result<Long> getTeamOwnerId(@PathVariable Long teamId) {
        Team team = teamMapper.selectById(teamId);
        if (team == null) {
            return (Result<Long>) (Result<?>) Result.success();
        }
        return Result.of(team.getOwnerUserId());
    }
}
