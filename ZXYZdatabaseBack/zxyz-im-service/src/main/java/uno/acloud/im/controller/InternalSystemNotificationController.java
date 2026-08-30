package uno.acloud.im.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.im.application.InternalSystemNotificationService;
import uno.acloud.im.dto.InternalBatchSystemNotificationRequest;

@Hidden
@RestController
@RequestMapping("/api/internal/im/system-notifications")
@Tag(name = "系统通知（内部）", description = "内部服务系统通知 API")
public class InternalSystemNotificationController {

    private final InternalSystemNotificationService internalSystemNotificationService;

    public InternalSystemNotificationController(InternalSystemNotificationService internalSystemNotificationService) {
        this.internalSystemNotificationService = internalSystemNotificationService;
    }

    @Operation(summary = "批量发送系统通知")
    @PostMapping("/batch")
    public Result<Void> batchNotify(@Valid @RequestBody InternalBatchSystemNotificationRequest request) {
        internalSystemNotificationService.batchNotify(request);
        return Result.success();
    }
}
