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
@ToString(exclude = {"oldPassword", "newPassword"})
@Schema(description = "密码修改请求")
public class PasswordChangeRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "当前密码不能为空")
    @Size(max = 128, message = "密码长度不合法")
    @Schema(description = "当前密码")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH, message = PasswordPolicy.SIZE_MESSAGE)
    @Pattern(regexp = PasswordPolicy.COMPLEXITY_REGEX, message = PasswordPolicy.COMPLEXITY_MESSAGE)
    @Schema(description = "新密码（≥8 位且至少含字母与数字）")
    private String newPassword;
}
