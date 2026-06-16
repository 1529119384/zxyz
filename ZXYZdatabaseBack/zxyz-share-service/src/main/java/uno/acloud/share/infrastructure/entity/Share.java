package uno.acloud.share.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString(exclude = {"password"})
@NoArgsConstructor
@AllArgsConstructor
@TableName("share")
public class Share implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String shareKey;
    private Long userId;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private LocalDateTime expireTime;
    private Integer maxAccessCount;
    private Integer currentAccessCount;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
