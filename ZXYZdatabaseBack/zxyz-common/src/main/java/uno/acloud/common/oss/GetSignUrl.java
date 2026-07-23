package uno.acloud.common.oss;

import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.PresignOptions;
import com.aliyun.sdk.service.oss2.models.GetObjectRequest;
import com.aliyun.sdk.service.oss2.models.GetObjectResult;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.PresignResult;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.util.FileNameUtil;
import uno.acloud.common.util.oss.OssContentDispositionUtil;
import uno.acloud.exception.BusinessException;

import java.time.Instant;
import java.util.Locale;

import static uno.acloud.common.util.FileNameUtil.BLOCKED_EXTENSIONS;

@Slf4j
@Component
@ConditionalOnClass(OSSClient.class)
public class GetSignUrl {

    @Value("${app.oss.sign-expire-seconds:3600}")
    private long signExpireSeconds;
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final OSSProperties ossProperties;
    private final OSSClient ossClient;

    public GetSignUrl(OSSProperties ossProperties, OSSClient ossClient) {
        this.ossProperties = ossProperties;
        this.ossClient = ossClient;
    }

    public OssSignInfo generatePutSignInfo(String objectKey, String originalName) {
        validateFileExtension(originalName);
        return generatePutSignInfo(
                objectKey,
                originalName,
                resolveContentType(originalName),
                buildContentDisposition(originalName)
        );
    }

    public OssSignInfo generatePutSignInfo(String objectKey,
                                           String originalName,
                                           String contentType,
                                           String contentDisposition) {
        validateFileExtension(originalName);
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成上传签名失败：objectKey 不能为空");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成上传签名失败：originalName 不能为空");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成上传签名失败：contentType 不能为空");
        }
        if (contentDisposition == null || contentDisposition.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成上传签名失败：contentDisposition 不能为空");
        }

        String bucket = ossProperties.getBucket();
        String region = ossProperties.getRegion();
        Instant expireAt = Instant.now().plusMillis(signExpireSeconds * 1000L);
        String signedContentType = contentType.trim();
        String signedContentDisposition = contentDisposition.trim();

        try {
            PutObjectRequest request = PutObjectRequest.newBuilder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(signedContentType)
                    .contentDisposition(signedContentDisposition)
                    .build();

            PresignResult presignResult = ossClient.presign(
                    request,
                    PresignOptions.newBuilder().expiration(expireAt).build()
            );
            String fileUrl = buildFileUrl(bucket, region, objectKey);

            log.info("生成 OSS 上传签名成功，objectKey: {}, contentType: {}, expireAt: {}", objectKey, signedContentType, expireAt);
            return new OssSignInfo(
                    presignResult.url(),
                    objectKey,
                    fileUrl,
                    signedContentType,
                    signedContentDisposition,
                    expireAt.toEpochMilli()
            );
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成 OSS 上传签名异常，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }

    public String getFileUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成文件访问地址失败：objectKey 不能为空");
        }
        return buildFileUrl(ossProperties.getBucket(), ossProperties.getRegion(), objectKey);
    }

    public boolean objectExists(String objectKey) {
        try {
            HeadObjectRequest request = HeadObjectRequest.newBuilder()
                    .bucket(ossProperties.getBucket())
                    .key(objectKey)
                    .build();
            ossClient.headObject(request);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通过 HEAD 请求获取 OSS 对象的 Content-Length（字节）。
     * 对象不存在或请求失败时返回 null。
     */
    public Long getObjectSize(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            HeadObjectRequest request = HeadObjectRequest.newBuilder()
                    .bucket(ossProperties.getBucket())
                    .key(objectKey)
                    .build();
            var response = ossClient.headObject(request);
            return response.contentLength();
        } catch (Exception e) {
            log.warn("获取 OSS 对象大小失败，objectKey: {}", objectKey, e);
            return null;
        }
    }

    /**
     * 读取 OSS 对象的前 N 个字节，用于 magic bytes 文件类型检测。
     * 失败时返回 null（不影响上传流程）。
     */
    public byte[] readFirstBytes(String objectKey, int maxBytes) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            GetObjectRequest request = GetObjectRequest.newBuilder()
                    .bucket(ossProperties.getBucket())
                    .key(objectKey)
                    .build();
            GetObjectResult result = ossClient.getObject(request);
            byte[] buffer = new byte[maxBytes];
            int bytesRead = result.body().read(buffer);
            if (bytesRead <= 0) {
                return null;
            }
            return bytesRead == maxBytes ? buffer : java.util.Arrays.copyOf(buffer, bytesRead);
        } catch (Exception e) {
            log.debug("读取 OSS 对象头部字节失败，objectKey: {}", objectKey);
            return null;
        }
    }

    public String generateGetSignUrl(String objectKey, String originalName) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成下载签名失败：objectKey 不能为空");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "生成下载签名失败：originalName 不能为空");
        }
        validateFileExtension(originalName);

        Instant expireAt = Instant.now().plusMillis(signExpireSeconds * 1000L);
        try {
            GetObjectRequest request = GetObjectRequest.newBuilder()
                    .bucket(ossProperties.getBucket())
                    .key(objectKey)
                    .responseContentDisposition(buildContentDisposition(originalName))
                    .build();
            PresignResult presignResult = ossClient.presign(
                    request,
                    PresignOptions.newBuilder().expiration(expireAt).build()
            );
            log.info("生成 OSS 下载签名成功，objectKey: {}, expireAt: {}", objectKey, expireAt);
            return presignResult.url();
        } catch (Exception e) {
            log.error("生成 OSS 下载签名异常，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作失败，请稍后重试");
        }
    }

    private void validateFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int lastDot = lower.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }
        String ext = lower.substring(lastDot);
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型");
        }
    }

    private String resolveContentType(String originalName) {
        return MediaTypeFactory.getMediaType(originalName)
                .map(Object::toString)
                .orElse(DEFAULT_CONTENT_TYPE);
    }

    private String buildContentDisposition(String filename) {
        return OssContentDispositionUtil.buildAttachmentFileName(filename);
    }

    private String buildFileUrl(String bucket, String region, String objectKey) {
        if (ossProperties.getPublicBaseUrl() != null && !ossProperties.getPublicBaseUrl().isBlank()) {
            return trimTrailingSlash(ossProperties.getPublicBaseUrl()) + "/" + objectKey;
        }
        return "https://" + bucket + ".oss-" + region + ".aliyuncs.com/" + objectKey;
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
