package uno.acloud.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "默认团队设置请求")
public class DefaultTeamRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "团队ID不能为空")
    @Schema(description = "团队ID")
    private Long teamId;
}
