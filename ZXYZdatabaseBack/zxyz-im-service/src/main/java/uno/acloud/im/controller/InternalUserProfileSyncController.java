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
import uno.acloud.im.application.InternalUserProfileSyncService;
import uno.acloud.im.dto.InternalUserProfileSyncRequest;

@Hidden
@RestController
@RequestMapping("/api/internal/im/user-profiles")
@Tag(name = "用户资料同步（内部）", description = "内部服务用户资料同步 API")
public class InternalUserProfileSyncController {

    private final InternalUserProfileSyncService syncService;

    public InternalUserProfileSyncController(InternalUserProfileSyncService syncService) {
        this.syncService = syncService;
    }

    @Operation(summary = "同步用户资料")
    @PostMapping
    public Result<Void> syncUserProfile(@Valid @RequestBody InternalUserProfileSyncRequest request) {
        syncService.syncUserProfile(request);
        return Result.success();
    }
}
