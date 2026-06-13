package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "邮箱绑定请求")
public class EmailBindRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "邮箱不能为空")
    @Size(max = 255, message = "邮箱长度不能超过 255")
    @Email(message = "邮箱格式不正确")
    @Schema(description = "邮箱地址", example = "user@example.com")
    private String email;
}
