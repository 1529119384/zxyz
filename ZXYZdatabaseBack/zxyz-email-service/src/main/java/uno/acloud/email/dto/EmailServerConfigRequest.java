package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter @Setter @ToString
@Schema(description = "SMTP配置请求")
public class EmailServerConfigRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "配置名称不能为空")
    @Size(max = 100, message = "配置名称长度不能超过100")
    @Schema(description = "配置名称", example = "主邮件服务器")
    private String configName;

    @NotBlank(message = "SMTP服务器地址不能为空")
    @Size(max = 255, message = "服务器地址长度不能超过255")
    @Schema(description = "SMTP服务器地址", example = "smtp.example.com")
    private String host;

    @NotNull(message = "端口号不能为空")
    @Positive(message = "端口号必须为正整数")
    @Schema(description = "端口号", example = "587")
    private Integer port;

    @Size(max = 200, message = "用户名长度不能超过200")
    @Schema(description = "SMTP用户名")
    private String username;

    @Size(max = 200, message = "密码长度不能超过200")
    @Schema(description = "SMTP密码")
    private String password;

    @NotBlank(message = "发件人地址不能为空")
    @Email(message = "发件人邮箱格式不正确")
    @Size(max = 200, message = "发件人地址长度不能超过200")
    @Schema(description = "发件人地址", example = "noreply@example.com")
    private String fromAddress;

    @Size(max = 50, message = "传输策略长度不能超过50")
    @Schema(description = "传输策略", example = "STARTTLS")
    private String transportStrategy;
}
