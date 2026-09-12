package uno.acloud.gateway.filter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.acloud.common.InternalServiceHeaders;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RequestIdFilter} 是链路追踪的起点：它生成的 X-Request-Id 会被写进下游全部服务日志。
 * 三个不变量必须成立——透传客户端已有 ID、缺失时生成 32 位 ID、请求结束后清 MDC
 * （Reactor 线程复用，MDC 不清理会串到下一个请求）。
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void getOrder_runsAfterHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Test
    void filter_whenClientSuppliesRequestId_keepsIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header(InternalServiceHeaders.REQUEST_ID_HEADER, "client-id-123"));

        AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            downstream.set(ex);
            mdcDuringChain.set(MDC.get("requestId"));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstream.get().getRequest().getHeaders()
                .getFirst(InternalServiceHeaders.REQUEST_ID_HEADER)).isEqualTo("client-id-123");
        assertThat(mdcDuringChain.get()).isEqualTo("client-id-123");
    }

    @Test
    void filter_whenHeaderBlank_treatsAsMissingAndGeneratesOne() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/x")
                        .header(InternalServiceHeaders.REQUEST_ID_HEADER, "   "));

        AtomicReference<String> seen = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            seen.set(ex.getRequest().getHeaders().getFirst(InternalServiceHeaders.REQUEST_ID_HEADER));
            return Mono.empty();
        }).block();

        // 空白值不能当作有效链路 ID 透传下去，否则下游会共用同一个"空 ID"
        assertThat(seen.get()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void filter_whenNoHeader_generatesIdAndClearsMdcAfterCompletion() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));

        AtomicReference<String> seen = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            seen.set(ex.getRequest().getHeaders().getFirst(InternalServiceHeaders.REQUEST_ID_HEADER));
            return Mono.empty();
        }).block();

        assertThat(seen.get()).matches("[0-9a-f]{32}");
        assertThat(MDC.get("requestId")).isNull();
    }
}
