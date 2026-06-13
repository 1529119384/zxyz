package uno.acloud.project.dto.internal;

import jakarta.validation.constraints.NotNull;

/**
 * 内部存储配额检查请求 DTO，替代 InternalStorageController 中的 Map&lt;String, Object&gt;。
 */
public record CheckUploadQuotaRequest(
        @NotNull(message = "用户 ID 不能为空") Long userId,
        @NotNull(message = "团队 ID 不能为空") Long teamId,
        @NotNull(message = "空间类型不能为空") Integer spaceType,
        @NotNull(message = "项目 ID 不能为空") Long projectId,
        Long totalSize
) {
}
