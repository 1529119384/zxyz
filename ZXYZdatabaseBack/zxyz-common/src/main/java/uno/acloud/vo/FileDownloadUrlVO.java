package uno.acloud.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件下载链接")
public class FileDownloadUrlVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "文件ID")
    private Long fileId;

    @Schema(description = "下载链接。直下时为预签名 URL；流式下载时为后端 API 地址")
    private String downloadUrl;

    @Schema(description = "是否直传下载。true=前端直接下载，false=调后端流式下载接口")
    private Boolean directDownload;

    @Schema(description = "文件名（Content-Disposition 用）")
    private String fileName;
}
