package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import uno.acloud.common.SystemPermissionCodes;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.application.ConversationReadService;
import uno.acloud.im.application.ConversationService;
import uno.acloud.im.application.DirectConversationService;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.dto.CreateDirectConversationRequest;
import uno.acloud.im.dto.UpdateConversationReadRequest;
import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.ConversationSummaryVO;
import uno.acloud.im.vo.TeamConversationVO;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/im")
@Tag(name = "会话管理", description = "IM 会话列表、创建、已读状态")
public class ConversationController {

    private final ConversationService conversationService;
    private final DirectConversationService directConversationService;
    private final ConversationReadService conversationReadService;
    private final ImRealtimePushService realtimePushService;

    public ConversationController(ConversationService conversationService,
                                  DirectConversationService directConversationService,
                                  ConversationReadService conversationReadService,
                                  ImRealtimePushService realtimePushService) {
        this.conversationService = conversationService;
        this.directConversationService = directConversationService;
        this.conversationReadService = conversationReadService;
        this.realtimePushService = realtimePushService;
    }

    @Operation(summary = "获取我的会话列表")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/conversations")
    public Result<List<ConversationSummaryVO>> listMyConversations(@RequestParam(required = false) Long teamId) {
        return Result.of(conversationService.listMyConversations(ImAuthContext.currentUserId(), teamId));
    }

    @Operation(summary = "获取会话详情")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/conversations/{conversationId}")
    public Result<ConversationSummaryVO> getConversation(@PathVariable Long conversationId) {
        return Result.of(conversationService.getConversationSummary(ImAuthContext.currentUserId(), conversationId));
    }

    @Operation(summary = "创建单聊会话")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_CREATE)
    @PostMapping("/direct-conversations")
    public Result<ConversationSummaryVO> createDirectConversation(@Valid @RequestBody CreateDirectConversationRequest request) {
        return Result.of(directConversationService.createOrGet(
                ImAuthContext.currentUserId(),
                request == null ? null : request.getTeamId(),
                request == null ? null : request.getTargetUserId()
        ));
    }

    @Operation(summary = "获取团队会话")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_READ)
    @GetMapping("/teams/{teamId}/conversation")
    public Result<TeamConversationVO> getTeamConversation(@PathVariable Long teamId) {
        return Result.of(conversationService.getTeamConversation(ImAuthContext.currentUserId(), teamId));
    }

    @Operation(summary = "更新已读位置")
    @SaCheckPermission(SystemPermissionCodes.IM_CONVERSATION_READ)
    @PostMapping("/conversations/{conversationId}/read")
    public Result<ConversationReadVO> updateReadPosition(@PathVariable Long conversationId,
                                                         @Valid @RequestBody UpdateConversationReadRequest request) {
        ConversationReadService.UpdateReadResult result = conversationReadService.updateReadPosition(
                ImAuthContext.currentUserId(),
                conversationId,
                request == null ? null : request.getLastReadMessageId()
        );
        realtimePushService.pushReadUpdated(result.memberUserIds(), result.readState());
        return Result.of(result.readState());
    }
}
