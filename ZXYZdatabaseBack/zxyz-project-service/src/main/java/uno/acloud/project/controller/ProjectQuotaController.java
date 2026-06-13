package uno.acloud.project.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.project.dto.project.UpdateProjectQuotaRequest;
import uno.acloud.project.service.ProjectQuotaPort;
import uno.acloud.project.vo.project.ProjectVO;

@RestController
@RequestMapping("/api/project-quotas")
@Tag(name = "项目配额", description = "项目存储配额管理")
public class ProjectQuotaController {

    private final ProjectQuotaPort projectQuotaPort;

    public ProjectQuotaController(ProjectQuotaPort projectQuotaPort) {
        this.projectQuotaPort = projectQuotaPort;
    }

    @Operation(summary = "更新项目存储配额")
    @PatchMapping("/projects/{projectId}")
    public Result<ProjectVO> updateProjectQuota(@CurrentUser Long userId, @PathVariable Long projectId, @Valid @RequestBody UpdateProjectQuotaRequest request) {
        return Result.of(projectQuotaPort.updateProjectQuota(projectId, request, userId));
    }
}
