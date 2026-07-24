package uno.acloud.file.storage;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 上传信息
 */
@Getter
@AllArgsConstructor
@Schema(description = "上传信息")
public class UploadInfo {

    @Schema(description = "提供者标识")
    private String provider;

    @Schema(description = "上传地址。预签名提供者：PUT URL；非预签名：后端上传 API 地址")
    private String uploadUrl;

    @Schema(description = "对象键（确认上传时需要）")
    private String objectKey;

    @Schema(description = "文件访问 URL。预签名提供者才有，非预签名为 null")
    private String fileUrl;

    @Schema(description = "MIME 类型")
    private String contentType;

    @Schema(description = "Content-Disposition 头")
    private String contentDisposition;

    @Schema(description = "预签名过期时间（epoch 毫秒），非预签名为 null")
    private Long expireAt;

    @Schema(description = "是否直传。true=前端传到后端 multipart 端点，false=前端直传存储（预签名 PUT）")
    private boolean directUpload;
}
