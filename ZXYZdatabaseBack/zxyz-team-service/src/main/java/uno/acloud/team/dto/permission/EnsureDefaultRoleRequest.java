package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 确保默认角色请求（供内部微服务调用）
 */
@Getter
@Setter
@ToString
public class EnsureDefaultRoleRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    private String username;
}
