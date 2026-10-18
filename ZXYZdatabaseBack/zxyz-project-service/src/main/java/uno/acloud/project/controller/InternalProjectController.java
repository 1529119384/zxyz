package uno.acloud.project.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.project.dto.internal.ProjectAccessCheckRequest;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.service.ProjectAccessGuardPort;

/**
 * 内部服务项目查询 API，承接原 InternalUserController 中项目域端点。
 */
@Hidden
@RestController
@RequestMapping("/api/internal/projects")
@Tag(name = "项目管理（内部）", description = "内部服务项目查询 API")
public class InternalProjectController {

    private final ProjectAccessGuardPort projectAccessGuardPort;

    public InternalProjectController(ProjectAccessGuardPort projectAccessGuardPort) {
        this.projectAccessGuardPort = projectAccessGuardPort;
    }

    @Operation(summary = "统计用户负责的活跃项目数")
    @GetMapping("/{userId}/active-projects-led-count")
    public Result<Integer> countActiveProjectsLedBy(@PathVariable Long userId) {
        return Result.of(projectAccessGuardPort.countActiveProjectsLedBy(userId));
    }

    @Operation(summary = "检查项目文件访问权限")
    @PostMapping("/{projectId}/access-check")
    public Result<Void> checkProjectFileAccess(@PathVariable Long projectId,
                                         @Valid @RequestBody ProjectAccessCheckRequest body) {
        projectAccessGuardPort.requireProjectFileAccess(projectId, body.userId());
        return Result.success();
    }

    @Operation(summary = "查询项目归属团队（文件侧 teamId 伪造校验用，65 号 #6）")
    @GetMapping("/{projectId}/team-id")
    public Result<Long> getProjectTeamId(@PathVariable Long projectId) {
        Long teamId = projectAccessGuardPort.getTeamId(projectId);
        if (teamId == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return Result.of(teamId);
    }

    @Operation(summary = "查询团队下所有项目配额总和")
    @GetMapping("/team/{teamId}/quota-sum")
    public Result<Long> sumProjectQuotaByTeam(@PathVariable Long teamId) {
        return Result.of(projectAccessGuardPort.sumProjectQuotaByTeam(teamId));
    }
}
