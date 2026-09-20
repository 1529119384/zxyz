package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import uno.acloud.starter.RestClientProperties;
import uno.acloud.starter.RestClients;

@Configuration
public class ImRestClientFactory {

    @Bean("imRestClient")
    public RestClient imRestClient(@LoadBalanced RestClient.Builder builder,
                                   AppImProperties properties,
                                   RestClientProperties restClientProperties) {
        // 超时统一取自 zxyz.http-client（P2-2）：原为就地硬编码 3s/10s
        return builder
                .baseUrl(properties.normalizedBaseUrl())
                .requestFactory(RestClients.requestFactory(restClientProperties))
                .build();
    }
}
