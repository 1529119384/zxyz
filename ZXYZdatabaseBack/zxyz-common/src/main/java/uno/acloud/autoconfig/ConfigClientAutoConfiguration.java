package uno.acloud.autoconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
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
 *     <p><b>该耦合已在删除前解除</b>（2026-09-21 核实）：{@code CacheConfig} 现只按
 *     {@code @ConditionalOnClass(RedisConnectionFactory.class)} 判断，不再依赖
 *     {@code ConfigGetter} Bean，故移除本类不会波及 {@code @EnableCaching}。
 *     <p><b>尚未删除的原因</b>：{@code admin-service} 的 {@code ConfigService} 仍在向
 *     {@code zxyz:config:changed} 频道发布消息，删除接收端会让「发布方以为有人在听」，
 *     属另一处需要一并拍板的取舍（报告 C-16）。
 *
 * <p><b>🔴 为什么改成了显式开关、且默认关闭</b>（2026-09-22，CI 取证）：
 * <p>归位（C-10）之前本类落在被 {@code @ComponentScan} 覆盖的包里，{@code @ConditionalOnBean}
 * 在 REGISTER_BEAN 阶段过早求值 ⇒ 恒 false ⇒ 两个 Bean <b>都没被创建</b>（线上即如此）。
 * 归位让条件第一次被正确求值 ⇒ 两个 Bean <b>真的被创建了</b>，于是一个「已废弃、全仓无消费方」
 * 的自动配置突然在<b>全部 10 个服务</b>上各拉起一个 Redis Pub/Sub 订阅 ——
 * 这是一次谁都没打算做的行为变更，并被 CI 抓到：{@code zxyz-im-service} 的上下文冒烟测试
 * 在没有 Redis 的 runner 上以 {@code RedisMessageListenerContainer.start() → 连接被拒} 失败
 * （本机有 Redis 所以一直是绿的，属「本地绿 / CI 红」的假绿）。
 * <p>原类级开关写的是 {@code app.admin-service.base-url}，但该属性在
 * {@code application-common.yml} 里是<b>全局存在</b>的 ⇒ 这个「开关」其实从未关住任何东西。
 * <p>因此换成真正的开关 {@code app.config-client.enabled}，<b>默认关闭</b>：
 * ① 不改变线上既有行为（归位前就是不在跑）；② 不让 10 个服务凭空多一个 Redis 启动期依赖；
 * ③ 不动 C-16 的取舍（要删就两端一并删）。本类是否最终保留仍待拍板。
 * 若要临时打开（例如验证发布端确有消费者），设 {@code app.config-client.enabled=true}；
 * 此时 {@code app.admin-service.base-url} 必须有值，否则启动期即报占位符解析失败（宁可响亮失败）。
 */
@Deprecated
@AutoConfiguration
@ConditionalOnClass(RestClient.class)
@ConditionalOnProperty(name = "app.config-client.enabled", havingValue = "true")
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
