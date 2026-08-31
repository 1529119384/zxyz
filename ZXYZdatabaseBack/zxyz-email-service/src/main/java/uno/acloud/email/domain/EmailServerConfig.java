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

    // ===== 领域行为 =====

    /** 配置是否启用。 */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(this.active);
    }

    /**
     * 返回脱敏副本：密码/密钥不暴露原值（统一替换为 "***"），原对象保持不变。
     * 便于在需要传递配置但不应泄露密钥的场景下使用。
     */
    public EmailServerConfig maskSecret() {
        EmailServerConfig copy = new EmailServerConfig();
        copy.setId(this.id);
        copy.setConfigName(this.configName);
        copy.setHost(this.host);
        copy.setPort(this.port);
        copy.setUsername(this.username);
        copy.setPasswordCipher(this.passwordCipher == null ? null : "***");
        copy.setFromAddress(this.fromAddress);
        copy.setTransportStrategy(this.transportStrategy);
        copy.setActive(this.active);
        copy.setLastTestStatus(this.lastTestStatus);
        copy.setLastTestTime(this.lastTestTime);
        copy.setLastTestMessage(this.lastTestMessage);
        copy.setCreateTime(this.createTime);
        copy.setUpdateTime(this.updateTime);
        return copy;
    }

    /** 是否支持给定的传输策略（按存储值精确比较）。 */
    public boolean supportsType(String transportStrategy) {
        return this.transportStrategy != null && this.transportStrategy.equals(transportStrategy);
    }
}
