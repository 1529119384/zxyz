package uno.acloud.email.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter @Setter @ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮件发送记录")
public class EmailRecordVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "记录ID")
    private Long id;

    @Schema(description = "收件人")
    private String recipient;

    @Schema(description = "邮件主题")
    private String subject;

    @Schema(description = "邮件内容（HTML）")
    private String contentHtml;

    @Schema(description = "发送状态")
    private String status;

    @Schema(description = "失败原因")
    private String failureReason;

    @Schema(description = "已尝试次数")
    private Integer attemptCount;

    @Schema(description = "最大尝试次数")
    private Integer maxAttempts;

    @Schema(description = "下次重试时间")
    private LocalDateTime nextRetryTime;

    @Schema(description = "定时发送时间")
    private LocalDateTime scheduledTime;

    @Schema(description = "实际发送时间")
    private LocalDateTime sentTime;

    @Schema(description = "邮件服务器配置ID")
    private Long serverConfigId;

    @Schema(description = "邮件服务器配置名称")
    private String serverConfigName;

    @Schema(description = "发件人用户名")
    private String senderUsername;

    @Schema(description = "业务类型")
    private String businessType;

    @Schema(description = "业务ID")
    private String businessId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
