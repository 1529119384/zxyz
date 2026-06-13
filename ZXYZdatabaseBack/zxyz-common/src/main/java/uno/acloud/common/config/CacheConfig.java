package uno.acloud.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

/**
 * Spring Cache 抽象层配置，使用 Redis 作为缓存后端。
 * <p>
 * 启用 {@code @EnableCaching} 后，服务方法可通过
 * {@code @Cacheable}、{@code @CacheEvict}、{@code @CachePut} 声明式缓存。
 * <p>
 * 自定义 TTL（缓存名 → 过期时间）：
 * <ul>
 *   <li>{@code team-permission} — 5 分钟（权限变更频率高）</li>
 *   <li>{@code project-access} — 10 分钟（项目成员变动相对低频）</li>
 * </ul>
 * 其他缓存名使用默认 TTL 30 分钟。
 * <p>
 * 此配置仅在 {@code spring-boot-starter-data-redis} 位于 classpath 时激活
 * （所有业务服务均已依赖该 starter，gateway 使用 reactive 版本不触发）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RedisConnectionFactory.class)
@EnableCaching
public class CacheConfig {

    /** 默认缓存 TTL：30 分钟 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** 缓存名 → 自定义 TTL 映射 */
    private static final Map<String, Duration> CUSTOM_TTL = new HashMap<>();

    static {
        CUSTOM_TTL.put("team-permission", Duration.ofMinutes(5));
        CUSTOM_TTL.put("project-access", Duration.ofMinutes(10));
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(om);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configMap = new HashMap<>();
        CUSTOM_TTL.forEach((name, ttl) ->
                configMap.put(name, defaultConfig.entryTtl(ttl)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configMap)
                .transactionAware()
                .build();
    }
}
