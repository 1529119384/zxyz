package uno.acloud.im.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.SystemNotificationService;
import uno.acloud.im.config.ImAuthContext;
import uno.acloud.im.vo.SystemNotificationVO;
import uno.acloud.im.vo.UnreadCountVO;

import java.util.List;

@SaCheckLogin
@RestController
@RequestMapping("/api/im/system-notifications")
@Tag(name = "系统通知", description = "系统通知列表、已读状态")
public class SystemNotificationController {

    private final SystemNotificationService systemNotificationService;

    public SystemNotificationController(SystemNotificationService systemNotificationService) {
        this.systemNotificationService = systemNotificationService;
    }

    @Operation(summary = "获取系统通知列表")
    @GetMapping
    public Result<List<SystemNotificationVO>> listNotifications(@RequestParam(defaultValue = "1") Integer page,
                                                                @RequestParam(required = false) Long teamId,
                                                                @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.of(systemNotificationService.listNotifications(ImAuthContext.currentUserId(), teamId, page, pageSize));
    }

    @Operation(summary = "获取未读通知数")
    @GetMapping("/unread-count")
    public Result<UnreadCountVO> getUnreadCount(@RequestParam(required = false) Long teamId) {
        return Result.of(systemNotificationService.getUnreadCount(ImAuthContext.currentUserId(), teamId));
    }

    @Operation(summary = "标记通知已读")
    @PatchMapping("/{notificationId}/read")
    public Result<Void> markRead(@PathVariable Long notificationId) {
        systemNotificationService.markRead(ImAuthContext.currentUserId(), notificationId);
        return Result.success();
    }
}
