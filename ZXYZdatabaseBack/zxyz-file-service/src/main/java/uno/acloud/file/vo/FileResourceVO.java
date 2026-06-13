package uno.acloud.file.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@AllArgsConstructor
@Schema(description = "文件资源信息")
public class FileResourceVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID", example = "1")
    private Long id;
    @Schema(description = "文件类型：1-文件夹，2-文件", example = "2")
    private Integer fileType;
    @Schema(description = "原始文件名", example = "文档.txt")
    private String originalName;
    @Schema(description = "文件分类", example = "1")
    private Integer category;
    @Schema(description = "文件大小，单位字节", example = "1024")
    private Long fileSize;
    @Schema(description = "父目录ID")
    private Long parentId;
    @Schema(description = "团队ID")
    private Long teamId;
    @Schema(description = "删除状态：0-正常，1-回收站，2-已删除", example = "0")
    private Integer deleted;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "修改时间")
    private LocalDateTime modifyTime;

    public FileResourceVO(Long id,
                          Integer fileType,
                          String originalName,
                          Integer category,
                          Long fileSize,
                          Long parentId,
                          Integer deleted,
                          LocalDateTime createTime,
                          LocalDateTime modifyTime) {
        this(id, fileType, originalName, category, fileSize, parentId, null, deleted, createTime, modifyTime);
    }
}
