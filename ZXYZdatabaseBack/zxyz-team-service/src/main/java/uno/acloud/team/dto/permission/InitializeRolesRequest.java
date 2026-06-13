package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 初始化内置角色请求（供内部微服务调用）
 */
@Getter
@Setter
@ToString
public class InitializeRolesRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队 ID 不能为空")
    private Long teamId;

    @NotNull(message = "团队所有者用户 ID 不能为空")
    private Long ownerUserId;
}
