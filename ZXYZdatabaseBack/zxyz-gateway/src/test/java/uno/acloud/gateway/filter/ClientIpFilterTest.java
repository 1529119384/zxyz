package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientIpFilter} + {@link ClientIpResolver} 决定限流与下游安全逻辑看到的源 IP。
 * 核心不变量：<b>客户端自传的 XFF / X-Real-IP 一律不可信</b>；只有配置了可信代理、
 * 且真实远程地址落在可信网段内时，才从 XFF 取真实客户端 IP。
 */
class ClientIpFilterTest {

    private static GatewayProperties propsWithTrustedProxies(String trustedProxies) {
        GatewayProperties props = new GatewayProperties();
        props.getGateway().setTrustedProxies(trustedProxies);
        return props;
    }

    private static ServerWebExchange passThrough(ClientIpFilter filter, ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            downstream.set(ex);
            return Mono.empty();
        }).block();
        return downstream.get();
    }

    @Test
    void getOrder_isHighestPrecedenceToBeatForwardedHeaderFilter() {
        assertThat(new ClientIpFilter(new GatewayProperties()).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void filter_whenTrustedProxiesNotConfigured_usesRemoteAddressAndStripsForgedHeaders() {
        ClientIpFilter filter = new ClientIpFilter(new GatewayProperties());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .remoteAddress(new InetSocketAddress("198.51.100.9", 12345))
                        .header(ClientIpResolver.X_FORWARDED_FOR_HEADER, "1.2.3.4")
                        .header(ClientIpResolver.X_REAL_IP_HEADER, "6.6.6.6"));

        HttpHeaders headers = passThrough(filter, exchange).getRequest().getHeaders();

        // 功能默认关闭 ⇒ 只认真实远程地址；客户端伪造的两个头必须被清掉
        assertThat(headers.getFirst(ClientIpResolver.X_REAL_IP_HEADER)).isEqualTo("198.51.100.9");
        assertThat(headers.containsKey(ClientIpResolver.X_FORWARDED_FOR_HEADER)).isFalse();
    }

    @Test
    void filter_whenRemoteIsTrustedProxy_resolvesRealClientIpFromXff() {
        ClientIpFilter filter = new ClientIpFilter(propsWithTrustedProxies("172.18.0.0/16"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .remoteAddress(new InetSocketAddress("172.18.0.5", 12345))
                        .header(ClientIpResolver.X_FORWARDED_FOR_HEADER, "203.0.113.7, 172.18.0.5"));

        HttpHeaders headers = passThrough(filter, exchange).getRequest().getHeaders();

        // 从右向左跳过可信代理，取第一个非可信 IP
        assertThat(headers.getFirst(ClientIpResolver.X_REAL_IP_HEADER)).isEqualTo("203.0.113.7");
        assertThat(headers.containsKey(ClientIpResolver.X_FORWARDED_FOR_HEADER)).isFalse();
    }

    @Test
    void filter_whenRemoteNotTrusted_refusesClientSuppliedXff() {
        ClientIpFilter filter = new ClientIpFilter(propsWithTrustedProxies("172.18.0.0/16"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .remoteAddress(new InetSocketAddress("198.51.100.9", 12345))
                        .header(ClientIpResolver.X_FORWARDED_FOR_HEADER, "203.0.113.7"));

        assertThat(passThrough(filter, exchange).getRequest().getHeaders()
                .getFirst(ClientIpResolver.X_REAL_IP_HEADER)).isEqualTo("198.51.100.9");
    }

    @Test
    void filter_whenRemoteAddressMissing_fallsBackToUnknown() {
        ClientIpFilter filter = new ClientIpFilter(new GatewayProperties());

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));

        // 无远程地址时不写 null，写 "unknown"，避免下游拿 null 当 IP 用
        assertThat(passThrough(filter, exchange).getRequest().getHeaders()
                .getFirst(ClientIpResolver.X_REAL_IP_HEADER)).isEqualTo("unknown");
    }
}
