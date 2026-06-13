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
import java.util.Map;

@Getter @Setter @ToString
@Schema(description = "按模板批量发送邮件请求")
public class BatchSendTemplateEmailRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "收件人列表不能为空")
    @Schema(description = "收件人邮箱列表")
    private List<String> recipients;

    @NotBlank(message = "模板编码不能为空")
    @Size(max = 100, message = "模板编码长度不能超过100")
    @Schema(description = "模板编码", example = "TEAM_INVITE")
    private String templateCode;

    @Schema(description = "模板变量")
    private Map<String, Object> variables;

    @Size(max = 100, message = "业务类型长度不能超过100")
    @Schema(description = "业务类型")
    private String businessType;

    @Size(max = 100, message = "业务ID长度不能超过100")
    @Schema(description = "业务ID")
    private String businessId;

    @Schema(description = "定时发送时间")
    private LocalDateTime scheduledTime;
}
