package uno.acloud.email.dto;

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
@Schema(description = "邮件连通性测试结果")
public class EmailConnectivityTestVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "配置ID")
    private Long configId;

    @Schema(description = "测试状态")
    private String status;

    @Schema(description = "测试时间")
    private LocalDateTime testTime;

    @Schema(description = "测试结果消息")
    private String message;
}
