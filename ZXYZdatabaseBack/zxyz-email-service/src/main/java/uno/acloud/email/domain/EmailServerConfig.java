package uno.acloud.email.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@TableName("email_server_config")
public class EmailServerConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String configName;
    private String host;
    private Integer port;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String passwordCipher;
    private String fromAddress;
    private String transportStrategy;
    private Boolean active;
    private String lastTestStatus;
    private LocalDateTime lastTestTime;
    private String lastTestMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
