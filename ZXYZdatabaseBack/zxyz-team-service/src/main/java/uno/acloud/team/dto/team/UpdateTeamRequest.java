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
@Schema(description = "更新团队请求")
public class UpdateTeamRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @Size(max = 50, message = "团队名称长度不能超过50")
    @Schema(description = "团队名称", example = "新团队名称")
    private String name;
    @Size(max = 500, message = "团队头像URL长度不能超过500")
    @Schema(description = "团队头像URL")
    private String avatar;
    @Size(max = 200, message = "团队描述长度不能超过200")
    @Schema(description = "团队描述", example = "新的描述内容")
    private String description;
}
