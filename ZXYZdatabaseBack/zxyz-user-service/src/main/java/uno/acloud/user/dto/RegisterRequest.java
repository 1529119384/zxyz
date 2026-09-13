package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import uno.acloud.common.PasswordPolicy;

import java.io.Serializable;

@Getter
@Setter
@ToString(exclude = {"password"})
@Schema(description = "注册请求")
public class RegisterRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 64, message = "用户名长度为 2-64 个字符")
    @Schema(description = "用户名", example = "zhangsan")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.SIZE_MESSAGE)
    @Pattern(regexp = PasswordPolicy.COMPLEXITY_REGEX, message = PasswordPolicy.COMPLEXITY_MESSAGE)
    @Schema(description = "密码（≥8 位且至少含字母与数字）", example = "passw0rd123")
    private String password;
}
