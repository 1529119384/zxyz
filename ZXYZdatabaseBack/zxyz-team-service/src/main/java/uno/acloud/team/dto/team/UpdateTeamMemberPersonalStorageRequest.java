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
@Schema(description = "更新成员个人存储配额请求")
public class UpdateTeamMemberPersonalStorageRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @NotNull(message = "个人存储配额上限不能为空")
    @Positive(message = "个人存储配额上限必须为正数")
    @Schema(description = "个人存储配额上限，单位字节", example = "10737418240")
    private Long personalStorageLimit;
}
