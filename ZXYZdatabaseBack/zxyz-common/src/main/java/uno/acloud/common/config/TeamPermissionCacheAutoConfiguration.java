package uno.acloud.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import uno.acloud.common.permission.TeamPermissionLocalCache;

/**
 * {@link TeamPermissionLocalCache} 的自动配置：注册缓存 Bean + Redis Pub/Sub 失效监听。
 * <p>
 * ⚠️ <b>发布方在 team-service</b>（{@code TeamPermissionCacheService#evictTeam} /
 * {@code evictMember} 在清 Redis 缓存的同时向 {@link TeamPermissionLocalCache#INVALIDATION_TOPIC}
 * 发消息）。只有发布与监听成对存在，本地缓存才是「秒级失效」而不是「等 5 分钟 TTL」。
 * <b>改任一侧时都要确认对侧还在</b> —— 此前 {@code PermissionCache}（Sa-Token 用户权限缓存）
 * 就只有监听、没有发布方，等于失效链路是断的。
 * <p>
 * 消息体格式：{@code teamId}（整队失效）或 {@code teamId:userId}（单成员失效）。
 * <p>
 * 监听器仅在 {@link RedisConnectionFactory} 存在时注册；无 Redis 环境下缓存退化为
 * 纯 TTL 过期（功能仍正确，只是失效不及时）。
 * （team-service 自身也会加载本配置，但它不使用该缓存，收到消息只是空操作。）
 */
@Configuration
public class TeamPermissionCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TeamPermissionLocalCache teamPermissionLocalCache() {
        return new TeamPermissionLocalCache();
    }

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisMessageListenerContainer teamPermissionCacheListener(
            RedisConnectionFactory connectionFactory,
            TeamPermissionLocalCache cache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String body = new String(message.getBody());
                    if (body == null || body.isBlank()) {
                        return;
                    }
                    String[] parts = body.split(":", 2);
                    try {
                        if (parts.length == 1) {
                            cache.invalidateTeam(Long.valueOf(parts[0]));
                        } else {
                            cache.invalidateMember(Long.valueOf(parts[0]), Long.valueOf(parts[1]));
                        }
                    } catch (NumberFormatException e) {
                        // 消息体非预期格式：忽略，缓存会在 TTL 后自然过期，不影响正确性
                    }
                },
                new ChannelTopic(TeamPermissionLocalCache.INVALIDATION_TOPIC)
        );
        return container;
    }
}
