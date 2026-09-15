package uno.acloud.autoconfig;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import uno.acloud.common.permission.TeamPermissionLocalCache;

/**
 * {@link TeamPermissionLocalCache} 的自动配置：注册缓存 Bean + Redis Pub/Sub 失效监听。
 * <p>
 * ⚠️ <b>发布方在 team-service</b>（{@code TeamPermissionCacheService#evictTeam} /
 * {@code evictMember} 在清 Redis 缓存的同时向 {@link TeamPermissionLocalCache#INVALIDATION_TOPIC}
 * 发消息）。只有发布与监听成对存在，本地缓存才是「秒级失效」而不是「等 5 分钟 TTL」。
 * <p>
 * 消息体格式：{@code teamId}（整队失效）或 {@code teamId:userId}（单成员失效）。
 * <p>
 * <b>为什么这个类在 {@code uno.acloud.autoconfig} 包、而不是 {@code uno.acloud.common.config}</b>：
 * 各服务启动类普遍写了 {@code @ComponentScan(basePackages = {"uno.acloud.<svc>", "uno.acloud.common"})}，
 * 会把 {@code uno.acloud.common.config} 下的配置类也扫进来。被<b>组件扫描</b>收录的配置类
 * 先于自动配置注册，此时 {@code RedisAutoConfiguration} 还没注册
 * {@code RedisConnectionFactory} Bean ⇒ {@code @ConditionalOnBean} 恒为 false ⇒
 * <b>监听器静默不注册</b>（不报错，只是订阅不到）。
 * 实测后果：11 个服务里只有不写 {@code @ComponentScan} 的 gateway 订阅成功，
 * im/team/file 等全部收不到失效通知。
 * ⇒ 放到无人扫描的 {@code uno.acloud.autoconfig}，只经 {@code AutoConfiguration.imports}
 * 加载，并用 {@code after = RedisAutoConfiguration.class} 固定顺序。
 * <p>
 * 监听器仅在 {@link RedisConnectionFactory} 存在时注册；无 Redis 环境下缓存退化为
 * 纯 TTL 过期（功能仍正确，只是失效不及时）。
 */
@Slf4j
@AutoConfiguration(after = RedisAutoConfiguration.class)
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
        // 覆写 isAutoStartup() 返回 false，改由本方法末尾手动 start 并 try/catch：
        // 默认的自动启动会在 subscribe 失败时抛异常、把整个应用上下文拖挂 ——
        // 而「收不到失效通知」只应让缓存退化成 TTL 过期，不该让服务起不来。
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

    /**
     * 在容器刷新完成后启动上面的监听器，并吞掉启动失败。
     * <p>
     * 为什么要绕这一圈：{@code RedisMessageListenerContainer} 作为 {@code SmartLifecycle}，
     * 由 Spring 自动 start 时一旦 subscribe 失败（典型：本地/测试环境没有 Redis，
     * 或 Redis 暂未就绪）会直接抛异常、把整个应用上下文拖挂。
     * 但「收不到失效通知」的后果只是缓存退化成 TTL 过期，<b>不该让服务起不来</b>。
     * <p>
     * 启动时机选 {@link ContextRefreshedEvent}：它在 {@code finishRefresh()} 里发布，
     * 生产与 {@code @SpringBootTest} 都会触发（不像 {@code ApplicationRunner}，
     * 后者在 {@code @SpringBootTest} 下不一定执行）。
     * <p>
     * 注意<b>不能</b>在 {@code @Bean} 方法里手动调 {@code afterPropertiesSet()}：
     * 该方法是 {@code InitializingBean} 回调，Spring 随后还会再调一次 ⇒
     * {@code IllegalStateException: Container already initialized}。
     */
    @Bean
    @ConditionalOnBean(RedisConnectionFactory.class)
    public ApplicationListener<ContextRefreshedEvent> teamPermissionCacheListenerStarter(
            @Qualifier("teamPermissionCacheListener") RedisMessageListenerContainer teamPermissionCacheListener) {
        return event -> {
            try {
                teamPermissionCacheListener.start();
            } catch (Exception e) {
                log.warn("团队权限本地缓存的 Redis 失效监听启动失败；本实例将只依赖 TTL(5 分钟) 过期: {}",
                        e.toString());
            }
        };
    }
}
