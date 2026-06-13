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
import uno.acloud.im.application.InternalTeamSyncService;
import uno.acloud.im.dto.InternalTeamMemberRemovalRequest;
import uno.acloud.im.dto.InternalTeamMemberSyncRequest;
import uno.acloud.im.dto.InternalTeamSyncRequest;

@Hidden
@RestController
@RequestMapping("/api/im/internal/team-sync")
@Tag(name = "团队同步（内部）", description = "内部服务团队数据同步 API")
public class InternalTeamSyncController {

    private final InternalTeamSyncService syncService;

    public InternalTeamSyncController(InternalTeamSyncService syncService) {
        this.syncService = syncService;
    }

    @Operation(summary = "同步团队数据")
    @PostMapping
    public Result<Void> syncTeam(@Valid @RequestBody InternalTeamSyncRequest request) {
        syncService.syncTeam(request);
        return Result.success();
    }

    @Operation(summary = "同步团队资料")
    @PostMapping("/profile")
    public Result<Void> syncTeamProfile(@Valid @RequestBody InternalTeamSyncRequest request) {
        syncService.syncTeamProfile(request);
        return Result.success();
    }

    @Operation(summary = "同步成员数据")
    @PostMapping("/members")
    public Result<Void> syncMember(@Valid @RequestBody InternalTeamMemberSyncRequest request) {
        syncService.syncMember(request);
        return Result.success();
    }

    @Operation(summary = "同步移除成员")
    @PostMapping("/members/remove")
    public Result<Void> removeMember(@Valid @RequestBody InternalTeamMemberRemovalRequest request) {
        syncService.removeMember(request);
        return Result.success();
    }
}
