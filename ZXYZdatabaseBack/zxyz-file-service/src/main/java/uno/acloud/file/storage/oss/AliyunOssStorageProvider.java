package uno.acloud.file.storage.oss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.common.oss.GetSignUrl;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.oss.OSSDeleter;
import uno.acloud.file.infrastructure.oss.OSSMetadataUpdater;
import uno.acloud.file.storage.DownloadInfo;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.UploadInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 阿里云 OSS 存储提供者
 * <p>
 * 包装现有 OSS 代码，委托给 {@link GetSignUrl}、{@link OSSDeleter}、{@link OSSMetadataUpdater}。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.provider.oss.enabled", havingValue = "true", matchIfMissing = true)
public class AliyunOssStorageProvider implements StorageProvider {

    /** 危险文件扩展名黑名单 fallback（与 FileUploadService 一致） */
    private static final Set<String> FALLBACK_BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".scr", ".pif", ".com",
            ".js", ".vbs", ".vbe", ".ps1", ".psm1", ".msi",
            ".wsf", ".wsh", ".hta", ".cpl", ".msc", ".reg"
    );

    private final GetSignUrl getSignUrl;
    private final OSSDeleter ossDeleter;
    private final OSSMetadataUpdater ossMetadataUpdater;
    private final ConfigGetter configGetter;
    private final Set<String> blockedExtensions;

    public AliyunOssStorageProvider(GetSignUrl getSignUrl,
                                    OSSDeleter ossDeleter,
                                    OSSMetadataUpdater ossMetadataUpdater,
                                    ConfigGetter configGetter) {
        this.getSignUrl = getSignUrl;
        this.ossDeleter = ossDeleter;
        this.ossMetadataUpdater = ossMetadataUpdater;
        this.configGetter = configGetter;
        this.blockedExtensions = new LinkedHashSet<>(configGetter.getJsonSet(
                "app.file.upload.blocked-extensions", FALLBACK_BLOCKED_EXTENSIONS));
    }

    @Override
    public String providerId() {
        return "oss";
    }

    /**
     * 校验文件扩展名是否在黑名单中。
     *
     * @param fileName 原始文件名
     * @throws BusinessException 扩展名在黑名单中时抛出
     */
    private void validateBlockedExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        int lastDot = lower.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }
        String ext = lower.substring(lastDot);
        if (blockedExtensions.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型");
        }
    }

    @Override
    public String displayName() {
        return "阿里云 OSS";
    }

    @Override
    public boolean supportsPresignedUpload() {
        return true;
    }

    @Override
    public boolean supportsPresignedDownload() {
        return true;
    }

    @Override
    public UploadInfo generateUploadInfo(String objectKey, String originalName,
                                         String contentType, String contentDisposition) {
        validateBlockedExtension(originalName);
        OssSignInfo signInfo = getSignUrl.generatePutSignInfo(
                objectKey, originalName, contentType, contentDisposition);

        return new UploadInfo(
                providerId(),
                signInfo.getUploadUrl(),
                signInfo.getObjectKey(),
                signInfo.getFileUrl(),
                signInfo.getContentType(),
                signInfo.getContentDisposition(),
                signInfo.getExpireAt(),
                true  // OSS 支持直传
        );
    }

    @Override
    public DownloadInfo generateDownloadInfo(String objectKey, String originalName) {
        validateBlockedExtension(originalName);
        String downloadUrl = getSignUrl.generateGetSignUrl(objectKey, originalName);

        return new DownloadInfo(
                providerId(),
                downloadUrl,
                originalName,
                true  // OSS 支持直下
        );
    }

    @Override
    public long receiveUpload(String objectKey, InputStream inputStream,
                              String contentType, String contentDisposition) {
        throw new UnsupportedOperationException("OSS 使用预签名直传，不支持后端接收上传");
    }

    @Override
    public void streamDownload(String objectKey, OutputStream outputStream) {
        throw new UnsupportedOperationException("OSS 使用预签名直下，不支持后端流式下载");
    }

    @Override
    public boolean objectExists(String objectKey) {
        return getSignUrl.objectExists(objectKey);
    }

    @Override
    public Long getObjectSize(String objectKey) {
        return getSignUrl.getObjectSize(objectKey);
    }

    @Override
    public byte[] readFirstBytes(String objectKey, int maxBytes) {
        return getSignUrl.readFirstBytes(objectKey, maxBytes);
    }

    @Override
    public void deleteObject(String objectKey) {
        ossDeleter.delete(objectKey);
    }

    @Override
    public void deleteObjects(List<String> objectKeys) {
        ossDeleter.deleteBatch(objectKeys);
    }

    @Override
    public void updateContentDisposition(String objectKey, String originalName) {
        ossMetadataUpdater.updateDownloadFileName(objectKey, originalName);
    }

    @Override
    public boolean healthCheck() {
        try {
            // 通过检查一个不存在的对象来验证 OSS 连接是否可达
            // objectExists 内部处理了异常，返回 false 表示连接正常但对象不存在
            return getSignUrl.objectExists("__health_check__zxyz__");
        } catch (Exception e) {
            log.warn("OSS 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
}
