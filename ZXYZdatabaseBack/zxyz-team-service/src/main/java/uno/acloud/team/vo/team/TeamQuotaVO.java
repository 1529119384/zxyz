package uno.acloud.team.vo.team;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 团队配额视图对象（内部服务间调用）
 */
@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "团队配额信息")
public class TeamQuotaVO implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "团队ID")
    private Long teamId;
    @Schema(description = "成员上限", example = "50")
    private Integer memberLimit;
    @Schema(description = "存储配额上限，单位字节", example = "107374182400")
    private Long storageLimit;
}
