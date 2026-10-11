package uno.acloud.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.admin.domain.SysConfig;
import uno.acloud.admin.mapper.SysConfigAuditMapper;
import uno.acloud.admin.mapper.SysConfigMapper;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.util.JasyptEncryptor;

import java.util.concurrent.TimeUnit;

/**
 * 配置管理服务
 * <p>提供配置读取（含 Caffeine 本地缓存）和更新（含 Redis Pub/Sub 通知）功能。</p>
 *
 * <p><b>敏感键的加密与掩码（ISSUE/54 R2）</b>：密钥类配置（键名命中
 * {@link #isSensitive(String)}）落库时强制加密为 {@code ENC(ciphertext)}（与
 * {@link #get(String)} 的解密对称，已含 ENC 前缀的值识别后不再二次加密）；
 * 读取侧（{@link #get(String)}）对敏感键无条件回 {@link #SENSITIVE_MASK}——
 * 不依赖存储形态是否已加密，因此历史遗留的明文敏感值同样被掩住（下次 update 时
 * 自然转为 ENC）。{@link #get(String)} 对敏感键永远拿不到明文：消费密钥一律走
 * Nacos / 环境变量，sys_config 不是密钥源。</p>
 */
@Slf4j
@Service
public class ConfigService {

    private static final String NULL_SENTINEL = "§NULL§";
    private static final String REDIS_CHANNEL = "zxyz:config:changed";
    private static final String SENSITIVE_MASK = "ENC:****";

    /**
     * 敏感键标记子串。与审计掩码（P3-3）同一份判定，勿在别处复制第二份。
     * 配置化预留：当前为代码内置清单；若将来需要按环境增删，
     * 应提为 @ConfigurationProperties 并保持「子串匹配」语义与默认值兼容，
     * 同时同步收紧/放宽审计掩码口径（两处共用本清单，改动自动生效）。
     */
    static final String[] SENSITIVE_MARKERS = {
            "secret", "password", "pwd", "token", "accesskey", "access-key",
            "secretkey", "secret-key", "oss", "smtp", "jasypt", "encrypt",
    };

    private final SysConfigMapper configMapper;
    private final SysConfigAuditMapper auditMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final JasyptEncryptor jasyptEncryptor;

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public ConfigService(SysConfigMapper configMapper,
                         SysConfigAuditMapper auditMapper,
                         StringRedisTemplate stringRedisTemplate,
                         JasyptEncryptor jasyptEncryptor) {
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jasyptEncryptor = jasyptEncryptor;
    }

    /**
     * 获取配置值。
     * <p>优先从 Caffeine 缓存读取，缓存未命中时查询数据库。
     * 不存在的键会缓存 NULL_SENTINEL（5 分钟），避免重复查询。</p>
     * <p>敏感键返回 {@link #SENSITIVE_MASK} 掩码而非真实值（无论库中是明文还是
     * ENC 密文——读侧掩码不依赖存储形态，存量明文数据同样被掩住，ISSUE/54 R2）。</p>
     *
     * @param key 配置键
     * @return 配置值；键不存在时返回 null
     */
    public String get(String key) {
        String value = cache.get(key, k -> {
            SysConfig config = configMapper.selectByKey(k);
            if (config == null) {
                return NULL_SENTINEL;
            }
            // 敏感键无条件掩码：掩码值进缓存也无妨（update 会失效缓存）
            if (isSensitive(k)) {
                return SENSITIVE_MASK;
            }
            return jasyptEncryptor.decrypt(config.getConfigValue());
        });
        return NULL_SENTINEL.equals(value) ? null : value;
    }

    /**
     * 获取配置值并转换为指定类型。
     *
     * @param key  配置键
     * @param type 目标类型（支持 String、Integer、Long、Boolean）
     * @return 转换后的配置值；键不存在时返回 null
     * @throws IllegalArgumentException 不支持的目标类型
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        String value = get(key);
        if (value == null) {
            return null;
        }
        return switch (type.getSimpleName()) {
            case "String" -> (T) value;
            case "Integer", "int" -> (T) Integer.valueOf(value);
            case "Long", "long" -> (T) Long.valueOf(value);
            case "Boolean", "boolean" -> (T) Boolean.valueOf(value);
            default -> throw new IllegalArgumentException("不支持的配置值类型: " + type);
        };
    }

    /**
     * 更新配置值。
     * <p>敏感键先加密为 {@code ENC(ciphertext)} 再落库（已是 ENC 格式的值原样落库，
     * 避免双重加密）；更新数据库、记录审计日志（敏感值以掩码写入）、清除本地缓存，
     * 并在事务提交后通过 Redis Pub/Sub 通知其他服务。</p>
     *
     * @param key        配置键
     * @param value      新配置值
     * @param operatorId 操作人 ID
     */
    @Transactional
    public void update(String key, String value, Long operatorId) {
        // 读侧回的是掩码：把掩码原样提交回来没有信息量，只会把真实值改坏，直接拒绝
        if (isSensitive(key) && SENSITIVE_MASK.equals(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "敏感配置的新值不能是掩码占位符，请输入新的明文值");
        }
        String oldValue = get(key);
        configMapper.updateValue(key, encryptIfSensitive(key, value));
        // 审计表不得落盘明文密钥：敏感键（OSS/SMTP/密码等）以掩码写入，键名保持明文以便追踪是哪一项被改（P3-3）
        auditMapper.insert(key, maskIfSensitive(key, oldValue), maskIfSensitive(key, value), operatorId);
        cache.invalidate(key);
        log.info("配置已更新: key={}, operatorId={}", key, operatorId);

        // 在事务提交后发送通知，避免事务回滚时其他服务已清除缓存
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            stringRedisTemplate.convertAndSend(REDIS_CHANNEL, key);
                            log.debug("配置变更通知已发送: key={}", key);
                        } catch (Exception e) {
                            // 远程调用失败不得抛入事务同步链；本地缓存仍会按 TTL(1m) 自然过期兜底
                            log.warn("配置变更通知发送失败，等待本地缓存 TTL 过期后自愈: key={}", key, e);
                        }
                    }
                }
        );
    }

    /**
     * 对配置值做读侧脱敏：敏感键回掩码，非敏感键原样返回。
     * <p>供 {@code ConfigAdminController#listAll} 复用——列表接口与单键 GET 同源
     * 掩码口径，不把敏感值明文回给前端（ISSUE/54 §2.7，与 R2 同源）。</p>
     */
    public static String maskIfSensitive(String key, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return isSensitive(key) ? SENSITIVE_MASK : value;
    }

    /**
     * 判断配置键是否属于敏感类（密钥/口令）。
     * <p>采用子串匹配，覆盖常见命名（OSS/SMTP/Redis 口令、token、jasypt 密钥等）。
     * 清单见 {@link #SENSITIVE_MARKERS}，同时供落库加密、读侧掩码与审计掩码三方使用。</p>
     */
    static boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.toLowerCase();
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 敏感键的落库形态：明文加密为 {@code ENC(ciphertext)}；已是 ENC 格式的值
     * 识别后原样返回，避免双重加密（与 {@link #get(String)} 的解密对称，ISSUE/54 R2）。
     * 非敏感键原样透传，行为不变。
     */
    private String encryptIfSensitive(String key, String value) {
        if (!isSensitive(key) || value == null || jasyptEncryptor.isEncrypted(value)) {
            return value;
        }
        return jasyptEncryptor.encrypt(value);
    }
}
