package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class EmailRestClientFactory {

    @Bean("emailRestClient")
    public RestClient emailRestClient(@LoadBalanced RestClient.Builder builder,
                                      ServiceProperties serviceProperties) {
        return builder.baseUrl(serviceProperties.getEmailService().normalizedBaseUrl()).build();
    }
}
