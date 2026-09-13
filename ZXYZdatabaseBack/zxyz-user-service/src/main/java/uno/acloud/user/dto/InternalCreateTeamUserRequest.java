package uno.acloud.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import uno.acloud.common.PasswordPolicy;

import java.io.Serializable;

@Getter
@Setter
@ToString
public class InternalCreateTeamUserRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 64, message = "用户名长度为 2-64 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    // 只约束长度、不加复杂度：这条链路的调用方是 team-service 的建号流程，
    // 与注册/改密共享同一个最小长度即可，复杂度要求属该链路之外的行为变更。
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.SIZE_MESSAGE)
    private String password;

    private String name;
    private String email;
    private String phone;
    private Long defaultTeamId;
}
