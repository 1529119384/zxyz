package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
@Schema(description = "校验验证码请求")
public class CheckVerifyCodeRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 200, message = "邮箱长度不能超过200")
    @Schema(description = "邮箱地址", example = "user@example.com")
    private String email;

    @NotBlank(message = "验证码场景不能为空")
    @Size(max = 50, message = "场景长度不能超过50")
    @Schema(description = "验证码场景", example = "REGISTER")
    private String scene;

    @NotBlank(message = "验证码不能为空")
    @Size(max = 20, message = "验证码长度不能超过20")
    @Schema(description = "验证码", example = "123456")
    private String code;
}
