package uno.acloud.project.dto.internal;

import jakarta.validation.constraints.NotNull;

/**
 * 内部存储配额检查请求 DTO，替代 InternalStorageController 中的 Map&lt;String, Object&gt;。
 * 注意：teamId 和 projectId 根据 spaceType 可能为 null（个人空间时两者均为 null）
 */
public record CheckUploadQuotaRequest(
        @NotNull(message = "用户 ID 不能为空") Long userId,
        Long teamId,
        @NotNull(message = "空间类型不能为空") Integer spaceType,
        Long projectId,
        Long totalSize
) {
}
