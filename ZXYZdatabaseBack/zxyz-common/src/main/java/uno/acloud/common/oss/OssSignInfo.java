package uno.acloud.common.oss;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class OssSignInfo {

    /** 前端直传 OSS 的 PUT 地址 */
    private String uploadUrl;

    /** OSS 对象 key，供上传确认接口回传 */
    private String objectKey;

    /** 文件最终访问地址 */
    private String fileUrl;

    /** 参与签名的 Content-Type，前端上传时必须原样带上 */
    private String contentType;

    /** 参与签名的 Content-Disposition，前端上传时必须原样带上 */
    private String contentDisposition;

    /** 预签名过期时间戳，单位毫秒 */
    private Long expireAt;
}
