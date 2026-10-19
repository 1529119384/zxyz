package uno.acloud.team.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.team.service.impl.InternalTeamService;
import uno.acloud.team.vo.team.TeamMemberDetailVO;
import uno.acloud.team.vo.team.TeamQuotaVO;
import uno.acloud.team.vo.team.TeamVO;

import java.util.List;

/**
 * 内部服务团队查询 API（Hidden：不出现在对外文档）。
 *
 * <p>65/66 号 🟠-5 重构：业务全部下沉 {@link InternalTeamService}，
 * Controller 不再直注 Mapper（照 InternalProjectController Port 范式）。</p>
 */
@Hidden
@Tag(name = "团队管理（内部）", description = "内部服务团队查询 API")
@RestController
@RequestMapping("/api/internal/teams")
public class InternalTeamController {

    private final InternalTeamService internalTeamService;

    public InternalTeamController(InternalTeamService internalTeamService) {
        this.internalTeamService = internalTeamService;
    }

    @Operation(summary = "根据ID查询团队")
    @GetMapping("/{teamId}")
    public Result<TeamVO> getTeamById(@PathVariable Long teamId) {
        return Result.of(internalTeamService.getTeamById(teamId));
    }

    @Operation(summary = "查询用户所属团队ID列表")
    @GetMapping("/ids/by-user/{userId}")
    public Result<List<Long>> listTeamIdsByUserId(@PathVariable Long userId) {
        return Result.of(internalTeamService.listTeamIdsByUserId(userId));
    }

    @Operation(summary = "查询团队成员用户ID列表")
    @GetMapping("/{teamId}/members")
    public Result<List<Long>> listMemberUserIds(@PathVariable Long teamId) {
        return Result.of(internalTeamService.listMemberUserIds(teamId));
    }

    @Operation(summary = "查询团队活跃成员详情")
    @GetMapping("/{teamId}/members/{userId}")
    public Result<TeamMemberDetailVO> getActiveMember(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(internalTeamService.getActiveMember(teamId, userId));
    }

    @Operation(summary = "检查是否为团队活跃成员")
    @GetMapping("/{teamId}/members/{userId}/active")
    public Result<Boolean> isActiveMember(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(internalTeamService.isActiveMember(teamId, userId));
    }

    @Operation(summary = "查询成员个人存储配额")
    @GetMapping("/{teamId}/members/{userId}/storage-limit")
    public Result<Long> getMemberPersonalStorageLimit(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(internalTeamService.getMemberPersonalStorageLimit(teamId, userId));
    }

    @Operation(summary = "查询成员角色编码")
    @GetMapping("/{teamId}/members/{userId}/role")
    public Result<String> getMemberRoleCode(@PathVariable Long teamId, @PathVariable Long userId) {
        return Result.of(internalTeamService.getMemberRoleCode(teamId, userId));
    }

    @Operation(summary = "查询团队配额")
    @GetMapping("/{teamId}/quota")
    public Result<TeamQuotaVO> getTeamQuota(@PathVariable Long teamId) {
        return Result.of(internalTeamService.getTeamQuota(teamId));
    }

    @Operation(summary = "统计团队成员数量")
    @GetMapping("/{teamId}/member-count")
    public Result<Integer> countOccupiedMembers(@PathVariable Long teamId) {
        return Result.of(internalTeamService.countOccupiedMembers(teamId));
    }

    @Operation(summary = "查询团队所有者ID")
    @GetMapping("/{teamId}/owner-id")
    public Result<Long> getTeamOwnerId(@PathVariable Long teamId) {
        return Result.of(internalTeamService.getTeamOwnerId(teamId));
    }
}
