package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "文件更新请求")
public class FileUpdateRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @Size(max = 255, message = "文件名最多255个字符")
    @Schema(description = "新文件名", example = "新名称.txt")
    private String newName;
    @Schema(description = "目标父目录ID")
    private Long targetParentId;
    @Schema(description = "团队ID")
    private Long teamId;
}
