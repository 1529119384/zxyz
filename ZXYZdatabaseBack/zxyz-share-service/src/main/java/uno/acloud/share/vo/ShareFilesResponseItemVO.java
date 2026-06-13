package uno.acloud.share.vo;

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
@Schema(description = "分享文件列表项")
public class ShareFilesResponseItemVO implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID")
    private Long fileId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件类型")
    private Integer fileType;

    @Schema(description = "是否为文件夹")
    private Boolean isFolder;

    @Schema(description = "文件分类")
    private Integer category;

    @Schema(description = "删除状态")
    private Integer deleted;

    @Schema(description = "是否失效")
    private Boolean invalid;

    @Schema(description = "失效原因")
    private String invalidText;

    @Schema(description = "文件大小（字节）")
    private Long size;

    @Schema(description = "修改时间")
    private LocalDateTime modifyTime;
}
