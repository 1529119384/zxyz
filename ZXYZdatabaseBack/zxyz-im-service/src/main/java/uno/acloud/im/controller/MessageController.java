package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import uno.acloud.common.SystemPermissionCodes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.ImMessageService;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.application.MessageModerationService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.dto.RecallMessageRequest;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

import java.time.LocalDateTime;
import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/im")
@Tag(name = "消息管理", description = "IM 消息列表、搜索、撤回")
public class MessageController {

    private final ImMessageService imMessageService;
    private final MessageModerationService moderationService;
    private final ImRealtimePushService realtimePushService;

    public MessageController(ImMessageService imMessageService,
                             MessageModerationService moderationService,
                             ImRealtimePushService realtimePushService) {
        this.imMessageService = imMessageService;
        this.moderationService = moderationService;
        this.realtimePushService = realtimePushService;
    }

    @Operation(summary = "获取消息列表")
    @SaCheckPermission(SystemPermissionCodes.IM_MESSAGE_READ)
    @GetMapping("/conversations/{conversationId}/messages")
    public Result<List<ImMessageVO>> listMessages(@PathVariable Long conversationId,
                                                  @RequestParam(required = false) Long afterMessageId,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                                  LocalDateTime afterTime,
                                                  @RequestParam(required = false) Long beforeMessageId,
                                                  @RequestParam(required = false) Integer limit) {
        return Result.of(imMessageService.listMessages(
                ImAuthContext.currentUserId(),
                conversationId,
                afterMessageId,
                afterTime,
                beforeMessageId,
                limit
        ));
    }

    @Operation(summary = "搜索消息")
    @SaCheckPermission(SystemPermissionCodes.IM_MESSAGE_READ)
    @GetMapping("/conversations/{conversationId}/messages/search")
    public Result<List<ImMessageVO>> searchMessages(@PathVariable Long conversationId,
                                                    @RequestParam String keyword,
                                                    @RequestParam(required = false) Integer limit) {
        return Result.of(imMessageService.searchMessages(
                ImAuthContext.currentUserId(),
                conversationId,
                keyword,
                limit
        ));
    }

    @Operation(summary = "撤回消息")
    @SaCheckPermission(SystemPermissionCodes.IM_MESSAGE_SEND)
    @PostMapping("/messages/{messageId}/recall")
    public Result<MessageRecallVO> recallMessage(@PathVariable Long messageId,
                                                 @Valid @RequestBody(required = false) RecallMessageRequest request) {
        MessageModerationService.RecallResult result = moderationService.recall(
                ImAuthContext.currentUserId(),
                messageId,
                request
        );
        realtimePushService.pushMessageRecalled(result.memberUserIds(), result.recall());
        return Result.of(result.recall());
    }
}
