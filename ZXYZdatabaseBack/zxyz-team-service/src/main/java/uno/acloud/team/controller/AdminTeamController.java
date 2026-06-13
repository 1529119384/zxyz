package uno.acloud.team.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.team.dto.system.BroadcastSystemMessageRequest;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;
import uno.acloud.team.vo.team.TeamVO;
import uno.acloud.team.dto.system.ScheduledEmailBatchRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.service.AdminTeamPort;
import uno.acloud.team.service.EnterpriseTeamPort;

import java.util.List;

@Tag(name = "团队管理（管理后台）", description = "系统管理员团队操作")
@RestController
@RequestMapping("/api/admin/teams")
@SaCheckRole(SystemRoleCodes.SYSTEM_ADMIN)
public class AdminTeamController {

    private final EnterpriseTeamPort teamPort;
    private final AdminTeamPort adminTeamPort;

    public AdminTeamController(EnterpriseTeamPort teamPort, AdminTeamPort adminTeamPort) {
        this.teamPort = teamPort;
        this.adminTeamPort = adminTeamPort;
    }

    @Operation(summary = "创建团队")
    @PostMapping
    public Result<TeamVO> createTeam(@Valid @RequestBody CreateTeamRequest request) {
        return Result.of(teamPort.createTeam(request));
    }

    @Operation(summary = "查询团队列表")
    @GetMapping
    public Result<List<AdminTeamOverviewVO>> listTeams() {
        return Result.of(adminTeamPort.listTeams());
    }

    @Operation(summary = "更新团队配额")
    @PatchMapping("/{teamId}/quota")
    public Result<AdminTeamOverviewVO> updateTeamQuota(@PathVariable Long teamId, @Valid @RequestBody UpdateTeamQuotaRequest request) {
        return Result.of(adminTeamPort.updateTeamQuota(teamId, request));
    }

    @Operation(summary = "广播系统消息")
    @PostMapping("/system-messages")
    public Result<Void> broadcastSystemMessage(@Valid @RequestBody BroadcastSystemMessageRequest request) {
        adminTeamPort.broadcastSystemMessage(request);
        return Result.success();
    }

    @Operation(summary = "计划系统邮件批次")
    @PostMapping("/system-emails/scheduled-batches")
    public Result<Void> scheduleSystemEmailBatch(@Valid @RequestBody ScheduledEmailBatchRequest request) {
        adminTeamPort.scheduleSystemEmailBatch(request);
        return Result.success();
    }
}
