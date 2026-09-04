package uno.acloud.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.client.RestClient;
import uno.acloud.common.config.ConfigGetter;

/**
 * 单一配置客户端自动配置。
 * <p>ConfigGetter 是项目内唯一的 HTTP 配置消费客户端，
 * 禁止再自行构建另一套 HTTP 配置客户端副本（如历史上的 ConfigServiceClient）。</p>
 * <p>当 {@code app.admin-service.base-url} 配置存在时，自动创建
 * {@link ConfigGetter} Bean 并注册 Redis Pub/Sub 监听器，
 * 监听 {@code zxyz:config:changed} 频道；收到变更通知后调用
 * {@link ConfigGetter#onConfigChanged(String)} 清除本地缓存。</p>
 * <p>admin-service 自身不需要此配置（直接读数据库），
 * 因此通过 {@code @ConditionalOnProperty} 条件装配。</p>
 *
 * @deprecated P2-A1 已将全部动态配置键迁移到 Nacos（{@code zxyz-dynamic.yml}），
 *     本自动配置连同 {@link ConfigGetter} Bean 与 Redis Pub/Sub 监听将在下个版本删除。
 *     <p><strong>删除前必须先解除的耦合</strong>：{@code CacheConfig} 上有
 *     {@code @ConditionalOnBean(ConfigGetter.class)}，移除本处的 Bean 定义会导致
 *     {@code CacheConfig} 静默不再创建 —— {@code @EnableCaching} 失效、
 *     全服务缓存被静默关闭且无任何报错。务必先改掉该条件。
 */
@Deprecated
@Configuration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(name = "app.admin-service.base-url")
public class ConfigClientAutoConfiguration {

    /** 配置变更 Redis Pub/Sub 频道名 */
    private static final String CONFIG_CHANGED_TOPIC = "zxyz:config:changed";

    @Bean
    @ConditionalOnBean(RestClient.Builder.class)
    @ConditionalOnMissingBean
    public ConfigGetter configGetter(
            RestClient.Builder restClientBuilder,
            @org.springframework.beans.factory.annotation.Value("${app.admin-service.base-url}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${app.internal-service-token:}") String internalServiceToken,
            ObjectMapper objectMapper) {
        return new ConfigGetter(
                restClientBuilder.build(),
                baseUrl,
                internalServiceToken,
                objectMapper
        );
    }

    @Bean
    @ConditionalOnBean(ConfigGetter.class)
    public RedisMessageListenerContainer configChangeListener(
            RedisConnectionFactory connectionFactory,
            ConfigGetter configGetter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String key = new String(message.getBody());
                    configGetter.onConfigChanged(key);
                },
                new ChannelTopic(CONFIG_CHANGED_TOPIC)
        );
        return container;
    }
}
