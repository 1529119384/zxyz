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
}
