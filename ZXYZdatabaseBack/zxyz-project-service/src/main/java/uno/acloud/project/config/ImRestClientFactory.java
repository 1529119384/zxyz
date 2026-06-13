package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ImRestClientFactory {

    @Bean("imRestClient")
    public RestClient imRestClient(@LoadBalanced RestClient.Builder builder, AppImProperties properties) {
        // IM 服务地址只允许从 app.im.base-url 进入，避免不同客户端读取不同配置键。
        return builder.baseUrl(properties.normalizedBaseUrl()).build();
    }
}
