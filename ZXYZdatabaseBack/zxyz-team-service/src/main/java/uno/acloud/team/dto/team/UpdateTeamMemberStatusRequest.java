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
@Schema(description = "更新成员状态请求")
public class UpdateTeamMemberStatusRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull(message = "成员状态不能为空")
    @Schema(description = "成员状态", example = "1")
    private Integer status;
}
