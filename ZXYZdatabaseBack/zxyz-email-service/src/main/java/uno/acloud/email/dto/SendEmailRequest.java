package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@Schema(description = "发送邮件请求")
public class SendEmailRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "收件人不能为空")
    @Size(max = 200, message = "收件人长度不能超过200")
    @Schema(description = "收件人邮箱", example = "user@example.com")
    private String recipient;

    @NotBlank(message = "邮件主题不能为空")
    @Size(max = 500, message = "邮件主题长度不能超过500")
    @Schema(description = "邮件主题", example = "欢迎注册")
    private String subject;

    @NotBlank(message = "邮件内容不能为空")
    @Schema(description = "邮件内容（HTML）")
    private String contentHtml;

    @Size(max = 100, message = "业务类型长度不能超过100")
    @Schema(description = "业务类型")
    private String businessType;

    @Size(max = 100, message = "业务ID长度不能超过100")
    @Schema(description = "业务ID")
    private String businessId;

    @Schema(description = "定时发送时间")
    private LocalDateTime scheduledTime;
}
