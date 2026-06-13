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
@Schema(description = "文件列表项")
public class FileListItemVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件唯一标识，前端执行打开、删除、恢复等操作时需要使用 */
    @Schema(description = "文件ID", example = "1")
    private Long id;

    /** 文件类型，前端依赖它区分文件夹与文件的展示和交互 */
    @Schema(description = "文件类型：1-文件夹，2-文件", example = "2")
    private Integer fileType;

    /** 原始名称，用于文件列表直接展示 */
    @Schema(description = "原始文件名", example = "文档.txt")
    private String originalName;

    /** 文件分类，前端用它决定图标和类型标签 */
    @Schema(description = "文件分类", example = "1")
    private Integer category;

    /** 文件大小，列表展示文件体积时需要 */
    @Schema(description = "文件大小，单位字节", example = "1024")
    private Long fileSize;

    /** 父级目录 ID，前端在目录树切换和回跳时需要保留关联关系 */
    @Schema(description = "父目录ID")
    private Long parentId;

    /** 服务器存储路径，用于回收站显示原始位置 */
    @Schema(description = "服务器存储路径")
    private String storePath;

    /** 团队ID，null 表示个人空间 */
    @Schema(description = "团队ID")
    private Long teamId;

    /** 创建时间，前端列表展示基础时间信息 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    /** 修改时间，前端排序和展示最近更新时间需要 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Schema(description = "修改时间")
    private LocalDateTime modifyTime;

    public FileListItemVO(Long id,
                          Integer fileType,
                          String originalName,
                          Integer category,
                          Long fileSize,
                          Long parentId,
                          String storePath,
                          LocalDateTime createTime,
                          LocalDateTime modifyTime) {
        this(id, fileType, originalName, category, fileSize, parentId, storePath, null, createTime, modifyTime);
    }
}
