package uno.acloud.project.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.project.service.ProjectCatalogPort;
import uno.acloud.project.vo.project.ProjectVO;

@RestController
@RequestMapping("/api/project-lifecycle")
@Tag(name = "项目生命周期", description = "项目归档等生命周期管理")
public class ProjectLifecycleController {

    private final ProjectCatalogPort projectCatalogPort;

    public ProjectLifecycleController(ProjectCatalogPort projectCatalogPort) {
        this.projectCatalogPort = projectCatalogPort;
    }

    @Operation(summary = "归档项目")
    @PatchMapping("/projects/{projectId}/archive")
    public Result<ProjectVO> archiveProject(@CurrentUser Long userId, @PathVariable Long projectId) {
        return Result.of(projectCatalogPort.archiveProject(projectId, userId));
    }
}
