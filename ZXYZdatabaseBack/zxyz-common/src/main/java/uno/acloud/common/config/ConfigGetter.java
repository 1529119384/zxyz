package uno.acloud.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.concurrent.TimeUnit;

/**
 * 配置读取助手（标准消费方式）。
 * <p>提供带 Caffeine 本地缓存 + Redis Pub/Sub 失效的配置读取能力，
 * 是各服务读取热配置的统一入口。</p>
 *
 * <p>使用方式：注入后直接调用 {@link #get(String)} 或 {@link #get(String, Class)}。
 * 缓存自动通过 Redis Pub/Sub 频道 {@code zxyz:config:changed} 失效。</p>
 *
 * <p>子类应添加 {@code @Component}，本类不注册为 Spring Bean。
 * 构造参数由子类从各自的 {@code ServiceProperties} 注入后传入。</p>
 */
@Slf4j
public class ConfigGetter extends AbstractServiceClient {

    private static final String NULL_SENTINEL = "§NULL§";

    /** 本地缓存 TTL：1 分钟（配置变更通过 Redis Pub/Sub 主动失效） */
    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public ConfigGetter(RestClient restClient,
                        String baseUrl,
                        String internalServiceToken,
                        ObjectMapper objectMapper) {
        super(restClient, baseUrl, internalServiceToken, objectMapper);
    }

    @Override
    protected String serviceName() {
        return "配置服务";
    }

    /**
     * 获取字符串配置值。
     * <p>优先从本地 Caffeine 缓存读取，缓存未命中时通过 HTTP 调用 admin-service。
     * 不存在的键会缓存 NULL_SENTINEL（1 分钟），避免重复请求。</p>
     *
     * @param key      配置键
     * @param fallback 键不存在或读取失败时的默认值
     * @return 配置值；键不存在时返回 fallback
     */
    public String getString(String key, String fallback) {
        String value = get(key);
        return value != null ? value : fallback;
    }

    /**
     * 获取整数配置值。
     *
     * @param key      配置键
     * @param fallback 键不存在或解析失败时的默认值
     * @return 配置值；键不存在或解析失败时返回 fallback
     */
    public int getInt(String key, int fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("配置值解析为 int 失败，使用 fallback: key={}, value={}", key, value);
            return fallback;
        }
    }

    /**
     * 获取长整数配置值。
     *
     * @param key      配置键
     * @param fallback 键不存在或解析失败时的默认值
     * @return 配置值；键不存在或解析失败时返回 fallback
     */
    public long getLong(String key, long fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("配置值解析为 long 失败，使用 fallback: key={}, value={}", key, value);
            return fallback;
        }
    }

    /**
     * 获取 JSON 数组配置值并转换为字符串集合。
     * <p>适用于扩展名白名单/黑名单等 JSON 数组配置。</p>
     *
     * @param key      配置键
     * @param fallback 键不存在或解析失败时的默认值
     * @return 配置值转换后的字符串集合；键不存在或解析失败时返回 fallback
     */
    public java.util.Set<String> getJsonSet(String key, java.util.Set<String> fallback) {
        String value = get(key);
        if (value == null) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper().readTree(value);
            if (!node.isArray()) {
                log.warn("配置值不是 JSON 数组，使用 fallback: key={}, value={}", key, value);
                return fallback;
            }
            java.util.Set<String> result = new java.util.LinkedHashSet<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("配置值解析为 JSON 数组失败，使用 fallback: key={}, value={}", key, value, e);
            return fallback;
        }
    }

    /**
     * 获取配置值。
     * <p>优先从本地 Caffeine 缓存读取，缓存未命中时通过 HTTP 调用 admin-service。
     * 不存在的键会缓存 NULL_SENTINEL（1 分钟），避免重复请求。</p>
     *
     * @param key 配置键
     * @return 配置值；键不存在或读取失败时返回 null
     */
    public String get(String key) {
        String value = cache.get(key, k -> {
            try {
                JsonNode root = getJsonOptional("/api/admin/configs/" + k);
                if (root == null) {
                    return NULL_SENTINEL;
                }
                JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) {
                    return NULL_SENTINEL;
                }
                return data.isTextual() ? data.asText() : data.toString();
            } catch (BusinessException e) {
                if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                    return NULL_SENTINEL;
                }
                throw e;
            } catch (Exception e) {
                log.warn("读取配置失败（异常，视为键不存在）: key={}", k, e);
                return NULL_SENTINEL;
            }
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
     * 配置变更回调，供 Redis Pub/Sub 监听器调用。
     * <p>收到变更通知后清除对应键的本地缓存，
     * 下次 {@link #get(String)} 调用将从 admin-service 重新拉取。</p>
     *
     * @param key 变更的配置键
     */
    public void onConfigChanged(String key) {
        cache.invalidate(key);
        log.debug("配置变更通知，已清除本地缓存: key={}", key);
    }
}
