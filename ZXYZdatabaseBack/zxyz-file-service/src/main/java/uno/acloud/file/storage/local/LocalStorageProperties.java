package uno.acloud.file.storage.local;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储配置
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage.provider.local")
public class LocalStorageProperties {

    /**
     * 文件存储根目录
     */
    private String basePath = "/data/zxyz-files";

    /**
     * 最大磁盘用量（字节），默认 100GB
     */
    private long maxDiskUsageBytes = 100L * 1024 * 1024 * 1024;

    /**
     * 单文件最大大小（字节），默认 500MB
     */
    private long maxFileSizeBytes = 500L * 1024 * 1024;

    /**
     * 下载限速（字节/秒），0 表示不限速
     */
    private long downloadSpeedBytesPerSecond = 0;
}
