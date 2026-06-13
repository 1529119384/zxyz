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
}
