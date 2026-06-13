package uno.acloud.file.dto;

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
@Schema(description = "重命名文件请求")
public class RenameFileRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "文件ID不能为空")
    @Schema(description = "文件ID", example = "1")
    private Long fileId;

    @NotBlank(message = "新文件名不能为空")
    @Size(max = 255, message = "文件名最多255个字符")
    @Schema(description = "新文件名", example = "新名称.txt")
    private String newName;
}
