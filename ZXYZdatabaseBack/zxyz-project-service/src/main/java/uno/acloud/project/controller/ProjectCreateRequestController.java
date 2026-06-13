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
import uno.acloud.project.dto.project.ReviewProjectCreateRequest;
import uno.acloud.project.dto.project.SubmitProjectCreateRequest;
import uno.acloud.project.service.ProjectCreateRequestPort;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

@RestController
@RequestMapping("/api/project-create-requests")
@Tag(name = "项目创建审批", description = "项目创建申请与审批")
public class ProjectCreateRequestController {

    private final ProjectCreateRequestPort projectCreateRequestPort;

    public ProjectCreateRequestController(ProjectCreateRequestPort projectCreateRequestPort) {
        this.projectCreateRequestPort = projectCreateRequestPort;
    }

    @Operation(summary = "提交项目创建申请")
    @PostMapping("/teams/{teamId}")
    public Result<ProjectCreateRequestVO> submitProjectCreateRequest(@CurrentUser Long userId, @PathVariable Long teamId, @Valid @RequestBody SubmitProjectCreateRequest request) {
        return Result.of(projectCreateRequestPort.submitProjectCreateRequest(teamId, request, userId));
    }

    @Operation(summary = "获取待审批的项目创建申请列表")
    @GetMapping("/teams/{teamId}/pending")
    public Result<List<ProjectCreateRequestVO>> listPendingProjectCreateRequests(@CurrentUser Long userId, @PathVariable Long teamId) {
        return Result.of(projectCreateRequestPort.listPendingProjectCreateRequests(teamId, userId));
    }

    @Operation(summary = "审批通过项目创建申请")
    @SaCheckPermission("project:approve")
    @PostMapping("/{applicationId}/approve")
    public Result<ProjectVO> approveProjectCreateRequest(@CurrentUser Long userId, @PathVariable Long applicationId, @Valid @RequestBody(required = false) ReviewProjectCreateRequest request) {
        return Result.of(projectCreateRequestPort.approveProjectCreateRequest(applicationId, request, userId));
    }

    @Operation(summary = "审批驳回项目创建申请")
    @SaCheckPermission("project:approve")
    @PostMapping("/{applicationId}/reject")
    public Result<ProjectCreateRequestVO> rejectProjectCreateRequest(@CurrentUser Long userId, @PathVariable Long applicationId, @Valid @RequestBody(required = false) ReviewProjectCreateRequest request) {
        return Result.of(projectCreateRequestPort.rejectProjectCreateRequest(applicationId, request, userId));
    }
}
