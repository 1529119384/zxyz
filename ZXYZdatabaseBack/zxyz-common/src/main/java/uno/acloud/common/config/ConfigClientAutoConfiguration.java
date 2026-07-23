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
import uno.acloud.client.ConfigServiceClient;
import uno.acloud.common.config.ConfigGetter;

/**
 * 配置客户端自动配置。
 * <p>当 {@code app.admin-service.base-url} 配置存在时，
 * 自动创建 {@link ConfigServiceClient} Bean 并注册 Redis Pub/Sub 监听器，
 * 监听 {@code zxyz:config:changed} 频道。
 * 收到变更通知后调用 {@link ConfigServiceClient#onConfigChanged(String)} 清除本地缓存。</p>
 *
 * <p>admin-service 自身不需要此配置（直接读数据库），
 * 因此通过 {@code @ConditionalOnProperty} 条件装配。</p>
 */
@Configuration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(name = "app.admin-service.base-url")
public class ConfigClientAutoConfiguration {

    /** 配置变更 Redis Pub/Sub 频道名 */
    private static final String CONFIG_CHANGED_TOPIC = "zxyz:config:changed";

    @Bean
    @ConditionalOnBean(RestClient.Builder.class)
    @ConditionalOnMissingBean
    public ConfigServiceClient configServiceClient(
            RestClient.Builder restClientBuilder,
            @org.springframework.beans.factory.annotation.Value("${app.admin-service.base-url}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${app.internal-service-token:}") String internalServiceToken,
            ObjectMapper objectMapper) {
        return new ConfigServiceClient(
                restClientBuilder.build(),
                baseUrl,
                internalServiceToken,
                objectMapper
        );
    }

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
    @ConditionalOnBean(ConfigServiceClient.class)
    public RedisMessageListenerContainer configChangeListener(
            RedisConnectionFactory connectionFactory,
            ConfigServiceClient configServiceClient,
            ConfigGetter configGetter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    String key = new String(message.getBody());
                    configServiceClient.onConfigChanged(key);
                    configGetter.onConfigChanged(key);
                },
                new ChannelTopic(CONFIG_CHANGED_TOPIC)
        );
        return container;
    }
}
