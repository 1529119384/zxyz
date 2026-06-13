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
@Schema(description = "手机号绑定请求")
public class PhoneBindRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "手机号不能为空")
    @Size(max = 50, message = "手机号长度不能超过 50")
    @Pattern(regexp = "^\\+?[0-9][0-9\\-\\s]{5,48}[0-9]$", message = "手机号格式不正确")
    @Schema(description = "手机号", example = "+8613800138000")
    private String phone;
}
