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

import java.util.concurrent.TimeUnit;

/**
 * 配置管理服务
 * <p>提供配置读取（含 Caffeine 本地缓存）和更新（含 Redis Pub/Sub 通知）功能。</p>
 */
@Slf4j
@Service
public class ConfigService {

    private static final String NULL_SENTINEL = "§NULL§";
    private static final String REDIS_CHANNEL = "zxyz:config:changed";

    private final SysConfigMapper configMapper;
    private final SysConfigAuditMapper auditMapper;
    private final StringRedisTemplate stringRedisTemplate;

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public ConfigService(SysConfigMapper configMapper,
                         SysConfigAuditMapper auditMapper,
                         StringRedisTemplate stringRedisTemplate) {
        this.configMapper = configMapper;
        this.auditMapper = auditMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 获取配置值。
     * <p>优先从 Caffeine 缓存读取，缓存未命中时查询数据库。
     * 不存在的键会缓存 NULL_SENTINEL（5 分钟），避免重复查询。</p>
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
            return config.getConfigValue();
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
     * <p>更新数据库、记录审计日志、清除本地缓存，
     * 并在事务提交后通过 Redis Pub/Sub 通知其他服务。</p>
     *
     * @param key        配置键
     * @param value      新配置值
     * @param operatorId 操作人 ID
     */
    @Transactional
    public void update(String key, String value, Long operatorId) {
        String oldValue = get(key);
        configMapper.updateValue(key, value);
        auditMapper.insert(key, oldValue, value, operatorId);
        cache.invalidate(key);
        log.info("配置已更新: key={}, operatorId={}", key, operatorId);

        // 在事务提交后发送通知，避免事务回滚时其他服务已清除缓存
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        stringRedisTemplate.convertAndSend(REDIS_CHANNEL, key);
                        log.debug("配置变更通知已发送: key={}", key);
                    }
                }
        );
    }
}
