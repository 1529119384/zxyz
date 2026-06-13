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
@Schema(description = "SMTP配置信息")
public class EmailServerConfigVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "配置名称")
    private String configName;

    @Schema(description = "SMTP服务器地址")
    private String host;

    @Schema(description = "端口号")
    private Integer port;

    @Schema(description = "SMTP用户名")
    private String username;

    @Schema(description = "是否已设置密码")
    private Boolean passwordSet;

    @Schema(description = "发件人地址")
    private String fromAddress;

    @Schema(description = "传输策略")
    private String transportStrategy;

    @Schema(description = "是否启用")
    private Boolean active;

    @Schema(description = "最近测试状态")
    private String lastTestStatus;

    @Schema(description = "最近测试时间")
    private LocalDateTime lastTestTime;

    @Schema(description = "最近测试消息")
    private String lastTestMessage;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
