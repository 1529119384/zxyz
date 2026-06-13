package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@Setter
@ToString
@Schema(description = "确认上传请求")
public class ConfirmUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** OSS 对象 key，对应签名接口返回的 objectKey */
    @NotBlank(message = "OSS对象key不能为空")
    @Schema(description = "OSS对象key", example = "file/2026/06/abc123.txt")
    private String objectKey;

    /** 原始文件名 */
    @NotBlank(message = "原始文件名不能为空")
    @Schema(description = "原始文件名", example = "文档.txt")
    private String originalName;

    /** 文件大小，单位字节 */
    @NotNull(message = "文件大小不能为空")
    @Positive(message = "文件大小必须为正数")
    @Schema(description = "文件大小，单位字节", example = "1024")
    private Long fileSize;

    /** 父级目录 ID */
    @NotNull(message = "父目录ID不能为空")
    @Schema(description = "父目录ID", example = "1")
    private Long parentId;

    /** 团队ID，null 表示个人空间 */
    @Schema(description = "团队ID，null表示个人空间")
    private Long teamId;

    /** 空间类型：1-个人，2-团队，3-项目 */
    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;

    /** 项目组 ID，仅项目空间需要 */
    @Schema(description = "项目ID，仅项目空间需要")
    private Long projectId;
}
