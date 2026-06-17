package uno.acloud.file.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;
import uno.acloud.file.dto.InternalStorageQueryRequest;
import uno.acloud.file.service.impl.StorageCacheService;

import java.util.List;

/**
 * 内部存储用量查询接口（服务间调用）。
 * <p>
 * 同时提供 POST（JSON 请求体）和 GET（查询参数）两种访问方式。
 * 现有调用方使用 POST，GET 别名用于兼容 REST 语义。
 * 注意：GET 请求通过查询参数绑定，列表参数使用重复 key（如 {@code ?userIds=1&userIds=2}）。
 * <p>
 * 所有查询通过 {@link StorageCacheService} 缓存（30s TTL），避免每次执行 SUM 全表扫描。
 */
@Hidden
@RestController
@RequestMapping("/api/internal/storage")
@Tag(name = "存储统计（内部）", description = "内部服务存储统计 API")
public class InternalStorageController {

    private final StorageCacheService storageCacheService;

    public InternalStorageController(StorageCacheService storageCacheService) {
        this.storageCacheService = storageCacheService;
    }

    @Operation(summary = "统计活跃文件大小（POST）")
    @PostMapping("/sum-active")
    public Result<Long> sumActiveFileSize(@Valid @RequestBody InternalStorageQueryRequest request) {
        return doSumActiveFileSize(request);
    }

    @Operation(summary = "统计活跃文件大小（GET）")
    @GetMapping("/sum-active")
    public Result<Long> sumActiveFileSizeGet(@Valid InternalStorageQueryRequest request) {
        return doSumActiveFileSize(request);
    }

    private Result<Long> doSumActiveFileSize(InternalStorageQueryRequest request) {
        long sum = storageCacheService.sumActiveFileSize(
                request.getUserId(), request.getTeamId(),
                request.getSpaceType(), request.getProjectId());
        return Result.of(sum);
    }

    @Operation(summary = "统计个人存储用量（POST）")
    @PostMapping("/sum-personal")
    public Result<Long> sumPersonalStorageByUsers(@Valid @RequestBody InternalStorageQueryRequest request) {
        return doSumPersonalStorageByUsers(request);
    }

    @Operation(summary = "统计个人存储用量（GET）")
    @GetMapping("/sum-personal")
    public Result<Long> sumPersonalStorageByUsersGet(@Valid InternalStorageQueryRequest request) {
        return doSumPersonalStorageByUsers(request);
    }

    private Result<Long> doSumPersonalStorageByUsers(InternalStorageQueryRequest request) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return Result.of(0L);
        }
        return Result.of(storageCacheService.sumPersonalStorageByUsers(userIds));
    }

    @Operation(summary = "获取个人存储用量列表（POST）")
    @PostMapping("/personal-usage-list")
    public Result<List<PersonalStorageUsage>> listPersonalStorageUsageByUsers(@Valid @RequestBody InternalStorageQueryRequest request) {
        return doListPersonalStorageUsageByUsers(request);
    }

    @Operation(summary = "获取个人存储用量列表（GET）")
    @GetMapping("/personal-usage-list")
    public Result<List<PersonalStorageUsage>> listPersonalStorageUsageByUsersGet(@Valid InternalStorageQueryRequest request) {
        return doListPersonalStorageUsageByUsers(request);
    }

    private Result<List<PersonalStorageUsage>> doListPersonalStorageUsageByUsers(InternalStorageQueryRequest request) {
        List<Long> userIds = request.getUserIds();
        if (userIds == null || userIds.isEmpty()) {
            return Result.of(List.of());
        }
        return Result.of(storageCacheService.listPersonalStorageUsageByUsers(userIds));
    }

    @Operation(summary = "获取团队存储用量列表（POST）")
    @PostMapping("/team-usage-list")
    public Result<List<TeamStorageUsage>> listTeamStorageUsage(@Valid @RequestBody InternalStorageQueryRequest request) {
        return doListTeamStorageUsage(request);
    }

    @Operation(summary = "获取团队存储用量列表（GET）")
    @GetMapping("/team-usage-list")
    public Result<List<TeamStorageUsage>> listTeamStorageUsageGet(@Valid InternalStorageQueryRequest request) {
        return doListTeamStorageUsage(request);
    }

    private Result<List<TeamStorageUsage>> doListTeamStorageUsage(InternalStorageQueryRequest request) {
        List<Long> teamIds = request.getTeamIds();
        if (teamIds == null || teamIds.isEmpty()) {
            return Result.of(List.of());
        }
        return Result.of(storageCacheService.sumActiveFileSizeByTeamIds(teamIds));
    }
}
