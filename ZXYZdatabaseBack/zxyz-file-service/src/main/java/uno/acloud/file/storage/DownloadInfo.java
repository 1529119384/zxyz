package uno.acloud.file.storage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 下载信息
 */
@Getter
@AllArgsConstructor
@Schema(description = "下载信息")
public class DownloadInfo {

    @Schema(description = "提供者标识")
    private String provider;

    @Schema(description = "下载地址。预签名提供者：GET URL；非预签名：null")
    private String downloadUrl;

    @Schema(description = "原始文件名（用于 Content-Disposition）")
    private String originalName;

    @Schema(description = "是否直传下载。true=前端直下存储，false=前端调后端流式下载")
    private boolean directDownload;
}
