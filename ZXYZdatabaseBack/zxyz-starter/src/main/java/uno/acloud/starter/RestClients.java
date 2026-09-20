package uno.acloud.starter;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 按 {@link RestClientProperties} 装配 {@link JdkClientHttpRequestFactory} 的唯一实现点。
 *
 * <p>提取目的（P2-2）：此前 {@code RestClientAutoConfiguration}、{@code EmailRestClientFactory}、
 * {@code ImRestClientFactory} <b>三处各自复制了同一段「先建 HttpClient 设连接超时、再包成
 * JdkClientHttpRequestFactory 设读取超时」的代码</b>。三份副本一旦漂移，
 * 会出现「同一次链路里不同客户端超时不同」这种极难排查的差异。收敛到此处后，
 * 三处共用一条装配路径与一份配置。</p>
 */
public final class RestClients {

    private RestClients() {
    }

    /**
     * 构造带超时配置的请求工厂。
     *
     * @param properties 超时参数（不可为 null）
     * @return 配置好连接与读取超时的 JDK 请求工厂
     */
    public static JdkClientHttpRequestFactory requestFactory(RestClientProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));
        return factory;
    }
}
