package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ImRestClientFactory {

    @Bean("imRestClient")
    public RestClient imRestClient(@LoadBalanced RestClient.Builder builder, AppImProperties properties) {
        return builder
                .baseUrl(properties.normalizedBaseUrl())
                .build();
    }
}
