package uno.acloud.team.dto.system;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
public class ScheduledEmailBatchRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotBlank(message = "邮件主题不能为空")
    @Size(max = 500, message = "邮件主题长度不能超过500")
    private String subject;
    @NotBlank(message = "邮件正文不能为空")
    private String contentHtml;
    @NotNull(message = "定时发送时间不能为空")
    private LocalDateTime scheduledTime;
}
