package uno.acloud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.concurrent.TimeUnit;

/**
 * 配置服务 HTTP 客户端（基础版）。
 * <p>供各业务服务通过 HTTP 调用 admin-service 读取运行时配置，
 * 内置 Caffeine 本地缓存（5 分钟过期）减少远程调用开销。</p>
 *
 * <p>配置变更通过 Redis Pub/Sub 频道 {@code zxyz:config:changed} 通知，
 * 收到通知后自动清除对应键的本地缓存。</p>
 *
 * <p>子类应添加 {@code @Component}，本类不注册为 Spring Bean。
 * 构造参数由子类从各自的 {@code ServiceProperties} 注入后传入。</p>
 */
@Slf4j
public class ConfigServiceClient extends AbstractServiceClient {

    private static final String NULL_SENTINEL = "§NULL§";

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    public ConfigServiceClient(RestClient restClient,
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
     * 获取配置值。
     * <p>优先从本地 Caffeine 缓存读取，缓存未命中时通过 HTTP 调用 admin-service。
     * 不存在的键会缓存 NULL_SENTINEL（5 分钟），避免重复请求。</p>
     *
     * @param key 配置键
     * @return 配置值；键不存在时返回 null
     */
    public String get(String key) {
        String value = cache.get(key, k -> {
            try {
                JsonNode root = getJson("/api/admin/configs/" + k);
                JsonNode data = root.path("data");
                if (data.isMissingNode() || data.isNull()) {
                    return NULL_SENTINEL;
                }
                return data.isTextual() ? data.asText() : data.toString();
            } catch (BusinessException e) {
                // 404：配置键不存在，缓存 NULL_SENTINEL 避免重复请求
                if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                    return NULL_SENTINEL;
                }
                // 5xx 等其他错误不缓存，依赖 Resilience4j 重试 + 熔断
                throw e;
            }
        });
        return NULL_SENTINEL.equals(value) ? null : value;
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
