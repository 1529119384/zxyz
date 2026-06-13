package uno.acloud.project.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.service.ProjectCatalogPort;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

@RestController
@RequestMapping("/api/project-catalog")
@Tag(name = "项目目录", description = "项目列表与创建")
public class ProjectCatalogController {

    private final ProjectCatalogPort projectCatalogPort;

    public ProjectCatalogController(ProjectCatalogPort projectCatalogPort) {
        this.projectCatalogPort = projectCatalogPort;
    }

    @Operation(summary = "获取团队项目列表")
    @GetMapping("/teams/{teamId}/projects")
    public Result<List<ProjectVO>> listProjects(@CurrentUser Long userId, @PathVariable Long teamId) {
        return Result.of(projectCatalogPort.listVisibleProjects(teamId, userId));
    }

    @Operation(summary = "创建项目")
    @SaCheckPermission("project:create")
    @PostMapping("/teams/{teamId}/projects")
    public Result<ProjectVO> createProject(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody CreateProjectRequest request) {
        return Result.of(projectCatalogPort.createProject(teamId, request, userId));
    }
}
