package uno.acloud.project.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ImRestClientFactory {

    @Bean("imRestClient")
    public RestClient imRestClient(@LoadBalanced RestClient.Builder builder, AppImProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return builder.requestFactory(factory)
                .baseUrl(properties.normalizedBaseUrl()).build();
    }
}
