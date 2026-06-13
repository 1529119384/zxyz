package uno.acloud.project.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "更新项目配额请求")
public class UpdateProjectQuotaRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "存储配额上限，单位字节", example = "10737418240")
    @NotNull(message = "存储配额上限不能为空")
    @Positive(message = "存储配额上限必须为正数")
    private Long storageLimit;
}
