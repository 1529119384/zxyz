package uno.acloud.team.dto.team;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "更新团队配额请求")
public class UpdateTeamQuotaRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull(message = "成员上限不能为空")
    @Positive(message = "成员上限必须为正数")
    @Schema(description = "成员上限", example = "100")
    private Integer memberLimit;
    @NotNull(message = "存储配额上限不能为空")
    @Positive(message = "存储配额上限必须为正数")
    @Schema(description = "存储配额上限，单位字节", example = "107374182400")
    private Long storageLimit;
}
