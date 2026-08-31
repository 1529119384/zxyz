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
@TableName("verify_code")
public class VerifyCode implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String email;
    private String scene;
    private String code;
    private LocalDateTime expireTime;
    private Boolean used;
    private LocalDateTime usedTime;
    private String requestIp;
    private Long emailRecordId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // ===== 领域行为 =====

    /** 新建验证码时的初始化：标记为未使用，并设置过期时间。 */
    public void markCreated(LocalDateTime expireTime) {
        this.used = false;
        this.expireTime = expireTime;
    }

    /** 是否已过期（过期时间不晚于给定时刻即视为过期）。 */
    public boolean isExpired(LocalDateTime now) {
        return this.expireTime == null || !this.expireTime.isAfter(now);
    }

    /** 与用户输入的验证码比对（忽略大小写）。 */
    public boolean matches(String code) {
        return this.code != null && this.code.equalsIgnoreCase(code);
    }

    /** 标记为已使用，并记录使用时间。 */
    public void markUsed() {
        this.used = true;
        this.usedTime = LocalDateTime.now();
    }
}
