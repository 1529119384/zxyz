package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import uno.acloud.starter.RestClientProperties;
import uno.acloud.starter.RestClients;

@Configuration
public class EmailRestClientFactory {

    @Bean("emailRestClient")
    public RestClient emailRestClient(@LoadBalanced RestClient.Builder builder,
                                      ServiceProperties serviceProperties,
                                      RestClientProperties restClientProperties) {
        // 超时统一取自 zxyz.http-client（P2-2）：原为就地硬编码 3s/10s，与 starter、ImRestClientFactory 三份重复
        return builder.requestFactory(RestClients.requestFactory(restClientProperties))
                .baseUrl(serviceProperties.getEmailService().normalizedBaseUrl()).build();
    }
}
