package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 权限校验请求（供内部微服务调用）
 */
@Getter
@Setter
@ToString
public class PermissionCheckRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;

    @NotNull(message = "团队 ID 不能为空")
    private Long teamId;

    @NotBlank(message = "权限编码不能为空")
    private String permissionCode;
}
