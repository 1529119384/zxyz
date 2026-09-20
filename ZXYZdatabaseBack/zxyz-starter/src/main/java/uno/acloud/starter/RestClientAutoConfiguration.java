package uno.acloud.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * RestClient 基础设施自动配置（统一收敛到 zxyz-starter）。
 * <p>仅配置 HTTP 连接超时和读取超时，属于基础设施层参数，
 * 不在热配置消费范围内（不接入 ConfigGetter）；<b>P2-2 起取值来自
 * {@link RestClientProperties}（前缀 {@code zxyz.http-client}），默认值与原硬编码一致</b>。</p>
 * <p>两个 Bean 均以 {@code @ConditionalOnMissingBean(name=...)} 保护，
 * 允许各服务在需要自定义（如按服务命名的 RestClient）时自行覆盖，
 * 而不会误伤其它同类型 Bean（如 project-service 的 emailRestClient/imRestClient）。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties(RestClientProperties.class)
public class RestClientAutoConfiguration {

    @Bean
    @LoadBalanced
    @ConditionalOnMissingBean(name = "loadBalancedRestClientBuilder")
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnMissingBean(name = "restClient")
    public RestClient restClient(@LoadBalanced RestClient.Builder builder,
                                 RestClientProperties restClientProperties) {
        return builder.requestFactory(RestClients.requestFactory(restClientProperties)).build();
    }
}
