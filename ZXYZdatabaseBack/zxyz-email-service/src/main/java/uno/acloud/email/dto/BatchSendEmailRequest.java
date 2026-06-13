package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter @ToString
@Schema(description = "批量发送邮件请求")
public class BatchSendEmailRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "收件人列表不能为空")
    @Schema(description = "收件人邮箱列表")
    private List<String> recipients;

    @NotBlank(message = "邮件主题不能为空")
    @Size(max = 500, message = "邮件主题长度不能超过500")
    @Schema(description = "邮件主题", example = "系统通知")
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
