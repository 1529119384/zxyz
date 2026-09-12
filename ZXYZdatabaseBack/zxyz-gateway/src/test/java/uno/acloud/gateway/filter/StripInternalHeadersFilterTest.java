package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.acloud.common.InternalServiceHeaders;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StripInternalHeadersFilter} 是**安全边界**：外部经公网到网关不得携带
 * 内部服务头，否则可伪造来源标识绕过内部端点防线。这里把"无条件剥离 + 不误伤其它头"钉死。
 */
class StripInternalHeadersFilterTest {

    private final StripInternalHeadersFilter filter = new StripInternalHeadersFilter();

    private ServerWebExchange passThrough(ServerWebExchange exchange) {
        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            downstream.set(ex);
            return Mono.empty();
        }).block();
        return downstream.get();
    }

    @Test
    void getOrder_runsRightAfterClientIpFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    void filter_removesForgedInternalHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header(InternalServiceHeaders.TOKEN_HEADER, "forged-token")
                        .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, "forged-caller"));

        HttpHeaders headers = passThrough(exchange).getRequest().getHeaders();

        assertThat(headers.containsKey(InternalServiceHeaders.TOKEN_HEADER)).isFalse();
        assertThat(headers.containsKey(InternalServiceHeaders.CALLER_SERVICE_HEADER)).isFalse();
    }

    @Test
    void filter_keepsRequestIdAndOrdinaryHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header(InternalServiceHeaders.TOKEN_HEADER, "forged-token")
                        .header(InternalServiceHeaders.REQUEST_ID_HEADER, "keep-me")
                        .header("X-Custom", "keep"));

        HttpHeaders headers = passThrough(exchange).getRequest().getHeaders();

        // 只剥内部鉴权/来源头；链路追踪与业务头必须原样透传
        assertThat(headers.getFirst(InternalServiceHeaders.REQUEST_ID_HEADER)).isEqualTo("keep-me");
        assertThat(headers.getFirst("X-Custom")).isEqualTo("keep");
    }

    @Test
    void filter_whenNoInternalHeaders_stillPassesChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));

        assertThat(passThrough(exchange)).isNotNull();
    }
}
