package uno.acloud.email.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter @Setter @ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "邮件服务运行状态")
public class EmailRuntimeStatusVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否启用发送功能")
    private Boolean sendingEnabled;

    @Schema(description = "是否已配置活跃服务器")
    private Boolean activeServerConfigured;

    @Schema(description = "状态说明")
    private String message;
}
