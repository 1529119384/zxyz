package uno.acloud.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import uno.acloud.satoken.PermissionCache;

/**
 * 用户权限本地缓存自动配置（P1-A2 修复）。
 * <p>
 * 1. 注册 {@link PermissionCache} Bean（Caffeine 本地缓存），供各微服务的
 *    {@code RemoteStpInterfaceImpl} 在鉴权时读写。
 * 2. 注册 Redis Pub/Sub 监听器：订阅 {@link PermissionCache#INVALIDATION_TOPIC}
 *    （{@code zxyz:permission:changed}）。team-service 在用户角色/权限变更成功后发布
 *    userId 到该频道，本监听器收到后调用 {@link PermissionCache#invalidate(Object)}
 *    清除该用户本地缓存，实现跨节点秒级失效。
 * <p>
 * 监听器仅在 {@link RedisConnectionFactory} 存在时注册，避免无 Redis 环境启动失败。
 * （team-service 自身也会加载此配置，但其 PermissionCache 不被使用，订阅收到消息后
 *  仅作空操作失效，无副作用。）
 */
@Configuration
public class PermissionCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PermissionCache permissionCache() {
        return new PermissionCache();
    }

    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public RedisMessageListenerContainer permissionCacheListener(
            RedisConnectionFactory connectionFactory,
            PermissionCache permissionCache) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String userId = new String(message.getBody());
                    permissionCache.invalidate(userId);
                },
                new ChannelTopic(PermissionCache.INVALIDATION_TOPIC)
        );
        return container;
    }
}
