package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "文件复制请求")
public class FileCopyRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotNull(message = "目标父目录ID不能为空")
    @Schema(description = "目标父目录ID", example = "1")
    private Long targetParentId;
}
