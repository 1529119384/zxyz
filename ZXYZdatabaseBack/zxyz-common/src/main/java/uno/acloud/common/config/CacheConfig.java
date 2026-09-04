package uno.acloud.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import uno.acloud.common.config.ConfigGetter;

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
// ⚠️ 危险耦合（N7）：本类的激活条件依赖已废弃的 ConfigGetter Bean。
// 一旦按 P2-A1 计划删除 ConfigGetter，本配置类会「静默不再创建」——
// @EnableCaching 随之失效、全服务缓存被静默关闭，且不会有任何报错日志。
// 删除 ConfigGetter 前必须先把此条件改为不依赖它（例如改用 @ConditionalOnClass
// 或 RedisConnectionFactory 的 @ConditionalOnBean）。
@ConditionalOnBean(ConfigGetter.class)
@EnableCaching
// 重启生效（不可加 @RefreshScope）：本类是构建 RedisCacheManager 的 @Configuration，刷新会重建缓存管理器实例；
// 且缓存 TTL 需在建 Bean 时一次性写入 RedisCacheConfiguration，改为热更须重构为逐条 TTL 策略。
public class CacheConfig {

    /** 默认缓存 TTL（分钟） */
    private final int defaultTtlMinutes;
    /** team-permission 缓存 TTL（分钟） */
    private final int teamPermissionTtlMinutes;
    /** project-access 缓存 TTL（分钟） */
    private final int projectAccessTtlMinutes;

    public CacheConfig(@Value("${app.cache.default-ttl-minutes:30}") int defaultTtlMinutes,
                       @Value("${app.cache.team-permission-ttl-minutes:5}") int teamPermissionTtlMinutes,
                       @Value("${app.cache.project-access-ttl-minutes:10}") int projectAccessTtlMinutes) {
        this.defaultTtlMinutes = defaultTtlMinutes;
        this.teamPermissionTtlMinutes = teamPermissionTtlMinutes;
        this.projectAccessTtlMinutes = projectAccessTtlMinutes;
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

        Duration defaultTtl = Duration.ofMinutes(defaultTtlMinutes);

        Map<String, Duration> customTtl = new HashMap<>();
        customTtl.put("team-permission", Duration.ofMinutes(teamPermissionTtlMinutes));
        customTtl.put("project-access", Duration.ofMinutes(projectAccessTtlMinutes));

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(defaultTtl)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> configMap = new HashMap<>();
        customTtl.forEach((name, ttl) ->
                configMap.put(name, defaultConfig.entryTtl(ttl)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(configMap)
                .transactionAware()
                .build();
    }
}
