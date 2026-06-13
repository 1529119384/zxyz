package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 用户 ID 请求（供内部微服务调用）
 */
@Getter
@Setter
@ToString
public class UserIdRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;
}
