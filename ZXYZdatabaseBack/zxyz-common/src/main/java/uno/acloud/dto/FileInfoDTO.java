package uno.acloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件元数据（服务间传输）")
public class FileInfoDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID")
    private Long id;

    @Schema(description = "文件类型")
    private Integer fileType;

    @Schema(description = "UUID文件名")
    private String uuidName;

    @Schema(description = "原始文件名")
    private String originalName;

    @Schema(description = "文件分类")
    private Integer category;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "存储路径")
    private String storePath;

    @Schema(description = "团队ID")
    private Long teamId;

    @Schema(description = "父文件夹ID")
    private Long parentId;

    @Schema(description = "删除状态")
    private Integer deleted;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "修改时间")
    private LocalDateTime modifyTime;

    public boolean isFile() {
        return Integer.valueOf(FileNodeType.FILE).equals(fileType);
    }

    public boolean isFolder() {
        return Integer.valueOf(FileNodeType.FOLDER).equals(fileType);
    }

    public boolean isActive() {
        return Integer.valueOf(FileDeleteStatus.NORMAL).equals(deleted);
    }
}
