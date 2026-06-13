package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "联系方式验证请求")
public class ContactVerifyRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "验证类型不能为空")
    @Pattern(regexp = "(?i)email|phone", message = "验证类型只能是 email 或 phone")
    @Schema(description = "验证类型（email 或 phone）", example = "email")
    private String type;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码长度必须为6位")
    @Schema(description = "验证码", example = "123456")
    private String code;
}
