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
@Schema(description = "上传确认结果项")
public class UploadConfirmItemResultVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "客户端原始文件名", example = "文档.txt")
    private String clientOriginalName;

    @Schema(description = "最终存储文件名", example = "文档.txt")
    private String finalName;

    @Schema(description = "父目录ID")
    private Long parentId;

    @Schema(description = "处理状态", example = "success")
    private String status;

    @Schema(description = "文件ID")
    private Long fileId;

    @Schema(description = "文件访问URL")
    private String fileUrl;

    @Schema(description = "业务状态码", example = "0")
    private Integer code;

    @Schema(description = "提示信息")
    private String msg;
}
