package uno.acloud.project.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "转移项目负责人请求")
public class TransferProjectLeaderRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "新负责人用户ID", example = "2")
    @NotNull(message = "负责人用户ID不能为空")
    private Long leaderUserId;
}
