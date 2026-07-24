package uno.acloud.file.storage.local;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.storage.DownloadInfo;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.UploadInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 本地磁盘存储提供者
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.storage.provider.local.enabled", havingValue = "true")
@EnableConfigurationProperties(LocalStorageProperties.class)
public class LocalDiskStorageProvider implements StorageProvider {

    private final LocalStorageProperties properties;

    public LocalDiskStorageProvider(LocalStorageProperties properties) {
        this.properties = properties;
        log.info("本地存储初始化，basePath: {}", properties.getBasePath());
    }

    @Override
    public String providerId() {
        return "local";
    }

    @Override
    public String displayName() {
        return "本地磁盘";
    }

    @Override
    public boolean supportsPresignedUpload() {
        return false;
    }

    @Override
    public boolean supportsPresignedDownload() {
        return false;
    }

    @Override
    public UploadInfo generateUploadInfo(String objectKey, String originalName,
                                         String contentType, String contentDisposition) {
        return new UploadInfo(
                providerId(),
                "/api/files/uploads/direct",  // 后端上传 API 地址
                objectKey,
                null,  // 本地存储无预签名 URL
                contentType,
                contentDisposition,
                null,  // 无过期时间
                true   // 本地存储：前端直传到后端 multipart 端点
        );
    }

    @Override
    public DownloadInfo generateDownloadInfo(String objectKey, String originalName) {
        return new DownloadInfo(
                providerId(),
                null,  // 本地存储无预签名 URL
                originalName,
                true   // 本地存储：前端直连后端 stream 端点
        );
    }

    @Override
    public long receiveUpload(String objectKey, InputStream inputStream,
                              String contentType, String contentDisposition) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            Path targetPath = basePath.resolve(objectKey);

            // 创建父目录
            Files.createDirectories(targetPath.getParent());

            // 写入文件
            long bytesWritten = Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

            // 写入元数据 sidecar 文件
            writeMetaFile(targetPath, contentDisposition);

            log.info("本地存储上传成功，objectKey: {}, bytesWritten: {}", objectKey, bytesWritten);
            return bytesWritten;
        } catch (IOException e) {
            log.error("本地存储上传失败，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件上传失败");
        }
    }

    @Override
    public void streamDownload(String objectKey, OutputStream outputStream) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            Path sourcePath = basePath.resolve(objectKey);

            if (!Files.exists(sourcePath)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
            }

            // 如果配置了限速，包装限速输出流
            OutputStream targetStream = outputStream;
            if (properties.getDownloadSpeedBytesPerSecond() > 0) {
                targetStream = new ThrottledOutputStream(outputStream, properties.getDownloadSpeedBytesPerSecond());
            }

            Files.copy(sourcePath, targetStream);
            log.info("本地存储下载完成，objectKey: {}", objectKey);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("本地存储下载失败，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件下载失败");
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        Path basePath = Path.of(properties.getBasePath());
        return Files.exists(basePath.resolve(objectKey));
    }

    @Override
    public Long getObjectSize(String objectKey) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            return Files.size(basePath.resolve(objectKey));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.warn("获取本地文件大小失败，objectKey: {}", objectKey, e);
            return null;
        }
    }

    @Override
    public byte[] readFirstBytes(String objectKey, int maxBytes) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            Path filePath = basePath.resolve(objectKey);
            if (!Files.exists(filePath)) {
                return null;
            }
            int size = (int) Math.min(maxBytes, Files.size(filePath));
            if (size <= 0) {
                return new byte[0];
            }
            byte[] bytes = new byte[size];
            try (FileChannel channel = FileChannel.open(filePath, java.nio.file.StandardOpenOption.READ)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) {
                        break;
                    }
                }
            }
            return bytes;
        } catch (IOException e) {
            log.warn("读取本地文件头部字节失败，objectKey: {}", objectKey, e);
            return null;
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            Path filePath = basePath.resolve(objectKey);
            Path metaPath = basePath.resolve(objectKey + ".meta");

            Files.deleteIfExists(filePath);
            Files.deleteIfExists(metaPath);

            log.info("本地存储删除完成，objectKey: {}", objectKey);
        } catch (IOException e) {
            log.error("本地存储删除失败，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件删除失败");
        }
    }

    @Override
    public void deleteObjects(List<String> objectKeys) {
        objectKeys.forEach(this::deleteObject);
    }

    @Override
    public void updateContentDisposition(String objectKey, String originalName) {
        try {
            Path basePath = Path.of(properties.getBasePath());
            Path metaPath = basePath.resolve(objectKey + ".meta");

            String contentDisposition = buildContentDisposition(originalName);
            Files.writeString(metaPath, contentDisposition);

            log.info("本地存储更新 Content-Disposition 完成，objectKey: {}", objectKey);
        } catch (IOException e) {
            log.error("本地存储更新 Content-Disposition 失败，objectKey: {}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "更新文件元数据失败");
        }
    }

    private void writeMetaFile(Path targetPath, String contentDisposition) throws IOException {
        Path metaPath = Path.of(targetPath.toString() + ".meta");
        Files.writeString(metaPath, contentDisposition != null ? contentDisposition : "");
    }

    private String buildContentDisposition(String originalName) {
        // 简单实现，实际应该使用 OssContentDispositionUtil 的逻辑
        return "attachment; filename=\"" + originalName + "\"";
    }

    @Override
    public boolean healthCheck() {
        try {
            Path basePath = Path.of(properties.getBasePath());
            if (!Files.exists(basePath)) {
                log.warn("本地存储健康检查失败：basePath 不存在，path={}", basePath);
                return false;
            }
            if (!Files.isDirectory(basePath)) {
                log.warn("本地存储健康检查失败：basePath 不是目录，path={}", basePath);
                return false;
            }
            // 检查是否可写（尝试创建临时文件）
            Path tempFile = basePath.resolve(".zxyz_health_check_" + System.currentTimeMillis());
            try {
                Files.createFile(tempFile);
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("本地存储健康检查失败：basePath 不可写，path={}", basePath);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("本地存储健康检查失败: {}", e.getMessage());
            return false;
        }
    }
}
