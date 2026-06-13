package uno.acloud.project.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "创建项目请求")
public class CreateProjectRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    @Schema(description = "项目名称", example = "我的项目")
    @NotBlank(message = "项目名称不能为空")
    @Size(max = 50, message = "项目名称最多50个字符")
    private String name;
    @Schema(description = "项目描述", example = "项目描述内容")
    @Size(max = 200, message = "项目描述最多200个字符")
    private String description;
    @Schema(description = "负责人用户ID", example = "1")
    @NotNull(message = "负责人用户ID不能为空")
    private Long leaderUserId;
    @Schema(description = "存储配额上限，单位字节", example = "10737418240")
    private Long storageLimit;
}
