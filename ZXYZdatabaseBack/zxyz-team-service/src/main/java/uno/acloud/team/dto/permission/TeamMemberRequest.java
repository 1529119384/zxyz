package uno.acloud.team.dto.permission;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 团队成员操作请求（供内部微服务调用）
 */
@Getter
@Setter
@ToString
public class TeamMemberRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队 ID 不能为空")
    private Long teamId;

    @NotNull(message = "用户 ID 不能为空")
    private Long userId;
}
