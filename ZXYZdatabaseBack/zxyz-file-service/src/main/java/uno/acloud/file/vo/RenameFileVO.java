package uno.acloud.file.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "重命名结果")
public class RenameFileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID", example = "1")
    private Long id;

    @Schema(description = "新文件名", example = "新名称.txt")
    private String originalName;

    @Schema(description = "文件类型：1-文件夹，2-文件", example = "2")
    private Integer fileType;

    @Schema(description = "父目录ID")
    private Long parentId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "修改时间")
    private LocalDateTime modifyTime;
}
