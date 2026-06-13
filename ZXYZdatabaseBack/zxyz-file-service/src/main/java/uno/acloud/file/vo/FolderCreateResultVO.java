package uno.acloud.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件夹创建结果")
public class FolderCreateResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文件夹ID", example = "1")
    private Long id;

    @Schema(description = "文件夹名称", example = "新建文件夹")
    private String originalName;

    @Schema(description = "文件类型", example = "1")
    private Integer fileType;

    @Schema(description = "父目录ID")
    private Long parentId;
}
