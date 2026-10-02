package uno.acloud.file.storage.oss;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * Nacos 注入的黑名单<b>原始 JSON 串</b>（F15，P3 并发/配置一致性）。
     *
     * <h2>为什么存原始串而不是解析后的 Set</h2>
     * <p>修复前这是构造期一次性固化的 {@code Set}：本类没有 {@code @RefreshScope}，
     * 于是 Nacos 热更黑名单后，<b>FileUploadService 立刻用新值、本 provider 层却仍用旧值</b>
     * （直到重启）。两层校验口径不一致 —— 例如动态把 {@code .svg} 加入黑名单，
     * 上传入口已拒绝，但若请求绕过入口直接走 provider，或将来入口改回宽松，provider 层仍是旧的允许集。</p>
     * <p>改为存原始串、<b>每次调用时解析</b>（与 {@code FileUploadService.allowedExtensions()}/
     * {@code blockedExtensions()} 的做法一致）。@RefreshScope 会让本 bean 在配置变更时重建，
     * 但那会连带重建依赖 {@link GetSignUrl} 的整条链；逐次解析更轻且与上传入口同源同频。</p>
     * <p>解析成本可忽略：仅在「生成上传签名」这一低频路径上发生，且解析结果与上传入口出自同一段逻辑。</p>
     */
    private final String blockedExtensionsRaw;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AliyunOssStorageProvider(GetSignUrl getSignUrl,
                                    OSSDeleter ossDeleter,
                                    OSSMetadataUpdater ossMetadataUpdater,
                                    @Value("${app.file.upload.blocked-extensions:}") String blockedExtensionsRaw) {
        this.getSignUrl = getSignUrl;
        this.ossDeleter = ossDeleter;
        this.ossMetadataUpdater = ossMetadataUpdater;
        this.blockedExtensionsRaw = blockedExtensionsRaw;
    }

    /** 每次调用时解析，使 Nacos 热更立即生效（F15）。 */
    private Set<String> blockedExtensions() {
        return new LinkedHashSet<>(parseBlockedExtensions(blockedExtensionsRaw, FALLBACK_BLOCKED_EXTENSIONS));
    }

    /**
     * 将 Nacos 注入的 JSON 数组字符串解析为扩展名集合，等价于 {@code ConfigGetter.getJsonSet}。
     * 属性缺失/为空时回退到 fallback；解析失败或非数组同样回退。
     */
    private static Set<String> parseBlockedExtensions(String raw, Set<String> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(raw);
            if (!node.isArray()) {
                log.warn("配置值不是 JSON 数组，使用 fallback: value={}", raw);
                return fallback;
            }
            Set<String> result = new LinkedHashSet<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("配置值解析为 JSON 数组失败，使用 fallback: value={}", raw, e);
            return fallback;
        }
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
        if (blockedExtensions().contains(ext)) {
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
                false  // OSS：前端直传存储（预签名 PUT）
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
            // 用一个确定不存在的探针对象验证连通性：
            // objectExists 只把「确定不存在」(404/NoSuchKey) 判为 false，其余错误上抛。
            // 因此「返回 false（404）」＝服务可达＝健康；抛异常＝不可达/鉴权失败＝不健康。
            // 旧实现直接把 objectExists 的返回值当健康结果，而探针对象本就不存在，
            // 于是健康检查恒定报告「提供者异常」。
            return !getSignUrl.objectExists("__health_check__zxyz__");
        } catch (Exception e) {
            log.warn("OSS 健康检查失败: {}", e.getMessage());
            return false;
        }
    }
}
