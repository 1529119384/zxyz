package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.ProjectConversationService;
import uno.acloud.im.dto.CreateProjectConversationRequest;
import uno.acloud.im.dto.ProjectCreateRequestMessageRequest;
import uno.acloud.im.dto.ProjectCreateRequestReviewResultRequest;
import uno.acloud.im.vo.ProjectConversationVO;

@SaCheckLogin
@RestController
@RequestMapping("/api/im/projects")
@Tag(name = "项目会话", description = "项目专属会话与创建申请消息")
public class ProjectConversationController {

    private final ProjectConversationService projectConversationService;

    public ProjectConversationController(ProjectConversationService projectConversationService) {
        this.projectConversationService = projectConversationService;
    }

    @Operation(summary = "创建或获取项目会话")
    @PostMapping("/conversations")
    public Result<ProjectConversationVO> createConversation(@Valid @RequestBody CreateProjectConversationRequest request) {
        return Result.of(projectConversationService.createOrGet(request));
    }

    @Operation(summary = "归档项目会话")
    @PatchMapping("/{projectId}/archive")
    public Result<Void> archiveConversation(@PathVariable Long projectId) {
        projectConversationService.archive(projectId);
        return Result.success();
    }

    @Operation(summary = "发送项目创建申请消息")
    @PostMapping("/creation-applications/messages")
    public Result<Void> appendProjectCreateRequestMessage(@Valid @RequestBody ProjectCreateRequestMessageRequest request) {
        projectConversationService.appendProjectCreateRequestMessage(request);
        return Result.success();
    }

    @Operation(summary = "发送项目创建申请结果消息")
    @PostMapping("/creation-applications/{applicationId}/result-messages")
    public Result<Void> appendProjectCreateRequestResultMessage(@PathVariable Long applicationId,
                                                                 @Valid @RequestBody ProjectCreateRequestReviewResultRequest request) {
        projectConversationService.appendProjectCreateRequestResultMessage(applicationId, request);
        return Result.success();
    }
}
