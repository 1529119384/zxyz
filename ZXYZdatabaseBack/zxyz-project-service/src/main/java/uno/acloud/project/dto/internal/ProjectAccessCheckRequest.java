package uno.acloud.project.dto.internal;

import jakarta.validation.constraints.NotNull;

/**
 * 内部项目文件访问检查请求 DTO，替代 InternalProjectController 中的 Map&lt;String, Long&gt;。
 */
public record ProjectAccessCheckRequest(
        @NotNull(message = "用户 ID 不能为空") Long userId
) {
}
