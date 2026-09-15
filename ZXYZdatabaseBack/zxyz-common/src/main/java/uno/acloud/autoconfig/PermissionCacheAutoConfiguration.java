package uno.acloud.autoconfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import uno.acloud.satoken.PermissionCache;

/**
 * 用户权限本地缓存（{@link PermissionCache}）的自动配置：注册缓存 Bean + Redis Pub/Sub 失效监听。
 * <p>
 * 消费方是各服务的 {@code RemoteStpInterfaceImpl}（Sa-Token 鉴权时读权限/角色）。
 * <p>
 * ⚠️ <b>本类此前存在两个缺陷，2026-09-15 一并修复</b>：
 * <ol>
 *   <li><b>发布方根本不存在</b> —— 全仓没有任何地方向
 *       {@link PermissionCache#INVALIDATION_TOPIC} 发消息，等于缓存只能等 TTL 自然过期。
 *       现已由 team-service 的 {@code TeamPermissionCacheService} 在角色/权限变更时发布。</li>
 *   <li><b>监听器实际上只有 gateway 注册成功</b> —— 本类原先放在
 *       {@code uno.acloud.common.config}，而各服务启动类普遍写了
 *       {@code @ComponentScan(basePackages = {"uno.acloud.<svc>", "uno.acloud.common"})}，
 *       会把它也扫进来；被组件扫描收录的配置类先于自动配置注册，此时
 *       {@code RedisAutoConfiguration} 还没注册 {@code RedisConnectionFactory} Bean ⇒
 *       {@code @ConditionalOnBean} 恒 false ⇒ 监听器静默不注册（不报错，只是订阅不到）。
 *       gateway 没写 {@code @ComponentScan}，所以唯独它正常。</li>
 * </ol>
 * ⇒ 因此本类放在无人扫描的 {@code uno.acloud.autoconfig}，只经
 * {@code AutoConfiguration.imports} 加载，并用 {@code after = RedisAutoConfiguration.class}
 * 固定顺序；同时保留 {@code @ConditionalOnBean} 守卫（{@code zxyz-starter} 没有 redis）。
 * <p>
 * 消息体格式：{@code userId}（单用户失效）或 {@code *}（全量失效，
 * 用于角色定义/权限分配这类无法按 userId 枚举的影响面）。
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
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
        // 覆写 isAutoStartup() 返回 false，改由本类末尾的 starter 手动 start 并 try/catch：
        // 默认自动启动在 subscribe 失败时会抛异常、把整个应用上下文拖挂，而「收不到失效
        // 通知」只应让缓存退化为 TTL 过期。
        // （RedisMessageListenerContainer 没有 setAutoStartup setter，只能靠覆写。）
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String body = new String(message.getBody());
                    if (body == null || body.isBlank()) {
                        return;
                    }
                    if (PermissionCache.INVALIDATE_ALL.equals(body.trim())) {
                        permissionCache.invalidateAll();
                        return;
                    }
                    permissionCache.invalidate(body);
                },
                new ChannelTopic(PermissionCache.INVALIDATION_TOPIC)
        );
        return container;
    }

    /**
     * 容器刷新完成后启动监听器，并吞掉启动失败。
     * <p>
     * 启动时机选 {@link ContextRefreshedEvent}（生产与 {@code @SpringBootTest} 都会触发；
     * 而 {@code ApplicationRunner} 在 {@code @SpringBootTest} 下不一定执行）。
     * <p>
     * 注意<b>不能</b>在 {@code @Bean} 方法里手动调 {@code afterPropertiesSet()}：
     * 它是 {@code InitializingBean} 回调，Spring 随后还会再调一次 ⇒
     * {@code IllegalStateException: Container already initialized}。
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public ApplicationListener<ContextRefreshedEvent> permissionCacheListenerStarter(
            @Qualifier("permissionCacheListener") RedisMessageListenerContainer permissionCacheListener) {
        return event -> {
            try {
                permissionCacheListener.start();
            } catch (Exception e) {
                log.warn("用户权限本地缓存的 Redis 失效监听启动失败；本实例将只依赖 TTL(5 分钟) 过期: {}",
                        e.toString());
            }
        };
    }
}
