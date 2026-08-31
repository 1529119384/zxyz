package uno.acloud.email.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("email_record")
public class EmailRecord implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String recipient;
    private String subject;
    private String contentHtml;
    private String status;
    private String failureReason;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryTime;
    private LocalDateTime scheduledTime;
    private LocalDateTime sentTime;
    private Long serverConfigId;
    private String serverConfigName;
    private String senderUsername;
    private String businessType;
    private String businessId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 领域行为（状态机 / 不变量） =====

    /**
     * 新建待发送记录时的初始化：状态置为 PENDING，尝试次数归零，并设置最大重试次数。
     * 业务规则（初始状态、计次）集中在此，而非散落在调用方。
     */
    public void initializeNew(int maxAttempts) {
        this.status = EmailRecordStatus.PENDING;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;
    }

    /** 标记发送成功：状态置为 SENT，并记录发送时间。 */
    public void markSent() {
        this.status = EmailRecordStatus.SENT;
        this.sentTime = LocalDateTime.now();
        this.failureReason = null;
        this.nextRetryTime = null;
    }

    /** 标记最终失败：状态置为 FAILED，并记录失败原因。 */
    public void markFailed(String reason) {
        this.status = EmailRecordStatus.FAILED;
        this.failureReason = reason;
        this.nextRetryTime = null;
    }

    /**
     * 安排重试：回到 PENDING，并写入下次重试时间。
     * 沿用现有枚举（仅有 PENDING/SENDING/SENT/FAILED），重试记录通过 PENDING + next_retry_time 被调度器捞起。
     */
    public void scheduleRetry(LocalDateTime nextRetryTime) {
        this.status = EmailRecordStatus.PENDING;
        this.nextRetryTime = nextRetryTime;
    }

    /** 是否还可以重试：当前尝试次数尚未达到上限。 */
    public boolean canRetry() {
        int attempts = this.attemptCount == null ? 0 : this.attemptCount;
        int max = this.maxAttempts == null ? 0 : this.maxAttempts;
        return attempts < max;
    }

    /** 是否已是终态（SENT / FAILED），不再参与调度。 */
    public boolean isTerminal() {
        return EmailRecordStatus.SENT.equals(this.status)
                || EmailRecordStatus.FAILED.equals(this.status);
    }
}
