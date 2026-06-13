package uno.acloud.project.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
import uno.acloud.common.web.CurrentUser;
import uno.acloud.project.dto.project.AddProjectMemberRequest;
import uno.acloud.project.dto.project.TransferProjectLeaderRequest;
import uno.acloud.project.service.ProjectMemberPort;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

@RestController
@RequestMapping("/api/project-members")
@Tag(name = "项目成员管理", description = "项目成员增删、负责人变更")
public class ProjectMemberController {

    private final ProjectMemberPort projectMemberPort;

    public ProjectMemberController(ProjectMemberPort projectMemberPort) {
        this.projectMemberPort = projectMemberPort;
    }

    @Operation(summary = "获取项目成员列表")
    @SaCheckPermission("project:member:list")
    @GetMapping("/projects/{projectId}/members")
    public Result<List<ProjectMemberVO>> listMembers(@CurrentUser Long userId, @PathVariable Long projectId) {
        return Result.of(projectMemberPort.listMembers(projectId, userId));
    }

    @Operation(summary = "添加项目成员")
    @SaCheckPermission("project:member:add")
    @PostMapping("/projects/{projectId}/members")
    public Result<ProjectMemberVO> addMember(@CurrentUser Long userId, @PathVariable Long projectId, @Valid @RequestBody AddProjectMemberRequest request) {
        return Result.of(projectMemberPort.addMember(projectId, request, userId));
    }

    @Operation(summary = "转让项目负责人")
    @SaCheckPermission("project:member:transfer")
    @PatchMapping("/projects/{projectId}/leader")
    public Result<ProjectVO> transferLeader(@CurrentUser Long userId, @PathVariable Long projectId, @Valid @RequestBody TransferProjectLeaderRequest request) {
        return Result.of(projectMemberPort.transferLeader(projectId, request, userId));
    }
}
