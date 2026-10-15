package uno.acloud.im.controller;

import io.swagger.v3.oas.annotations.Hidden;
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
import uno.acloud.common.audit.Log;
import uno.acloud.im.application.ProjectConversationService;
import uno.acloud.im.dto.CreateProjectConversationRequest;
import uno.acloud.im.dto.ProjectCreateRequestMessageRequest;
import uno.acloud.im.dto.ProjectCreateRequestReviewResultRequest;
import uno.acloud.im.vo.ProjectConversationVO;

/**
 * 项目会话内部端点（仅限服务间调用）。
 *
 * <p>60 号 🟠-1 收口：本组端点的唯一调用方是 project-service 的 {@code ImCollaborationClient}
 * （前端对 /api/im/projects/** 零引用，已 grep 核实），设计上就不该暴露在登录态通道。
 * 原挂在 /api/im/projects/** 下仅有 @SaCheckLogin，任意登录用户可伪造项目申请消息、
 * 归档/创建任意项目会话；现迁移至 /api/internal/im/projects/**，由
 * {@code ImHttpAuthInterceptor} 的内部前缀判断接管（X-Internal-Service-Token +
 * X-Internal-Caller-Service 白名单矩阵，fail-closed），Sa-Token 拦截器对该前缀豁免。</p>
 */
@Hidden
@RestController
@RequestMapping("/api/internal/im/projects")
@Tag(name = "项目会话（内部）", description = "项目专属会话与创建申请消息（仅服务间调用）")
public class ProjectConversationController {

    private final ProjectConversationService projectConversationService;

    public ProjectConversationController(ProjectConversationService projectConversationService) {
        this.projectConversationService = projectConversationService;
    }

    @Operation(summary = "创建或获取项目会话")
    @Log
    @PostMapping("/conversations")
    public Result<ProjectConversationVO> createConversation(@Valid @RequestBody CreateProjectConversationRequest request) {
        return Result.of(projectConversationService.createOrGet(request));
    }

    @Operation(summary = "归档项目会话")
    @Log
    @PatchMapping("/{projectId}/archive")
    public Result<Void> archiveConversation(@PathVariable Long projectId) {
        projectConversationService.archive(projectId);
        return Result.success();
    }

    @Operation(summary = "发送项目创建申请消息")
    @Log
    @PostMapping("/creation-applications/messages")
    public Result<Void> appendProjectCreateRequestMessage(@Valid @RequestBody ProjectCreateRequestMessageRequest request) {
        projectConversationService.appendProjectCreateRequestMessage(request);
        return Result.success();
    }

    @Operation(summary = "发送项目创建申请结果消息")
    @Log
    @PostMapping("/creation-applications/{applicationId}/result-messages")
    public Result<Void> appendProjectCreateRequestResultMessage(@PathVariable Long applicationId,
                                                                @Valid @RequestBody ProjectCreateRequestReviewResultRequest request) {
        projectConversationService.appendProjectCreateRequestResultMessage(applicationId, request);
        return Result.success();
    }
}
