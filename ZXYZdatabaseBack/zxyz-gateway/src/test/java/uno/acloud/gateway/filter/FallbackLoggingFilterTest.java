package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FallbackLoggingFilter} 只在命中兜底路由时告警，但它挂在
 * {@code LOWEST_PRECEDENCE - 1}，会经过**所有**请求。因此"路由不存在 / 非兜底路由时不得 NPE、
 * 不得误报"比"命中时告警"更重要。
 */
class FallbackLoggingFilterTest {

    private final FallbackLoggingFilter filter = new FallbackLoggingFilter();

    private static Route route(String id) {
        return Route.async()
                .id(id)
                .uri("http://zxyz-project-service")
                .predicate(exchange -> true)
                .build();
    }

    private static void run(FallbackLoggingFilter filter, MockServerWebExchange exchange) {
        filter.filter(exchange, ex -> Mono.empty()).block();
    }

    @Test
    void getOrder_isLowestPrecedenceMinusOne() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE - 1);
    }

    @Test
    void filter_whenRouteIsFallback_warnsUsingActualResponseStatus() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/unknown"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("fallback"));
        exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);

        run(filter, exchange);
    }

    @Test
    void filter_whenFallbackAndStatusNotSet_logsZeroInsteadOfThrowing() {
        // 响应状态还没被下游写出来时（getStatusCode() == null）不能 NPE
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/unknown"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("fallback"));

        run(filter, exchange);
    }

    @Test
    void filter_whenRouteIsNotFallback_skipsWarn() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route("zxyz-user-service"));

        run(filter, exchange);
    }

    @Test
    void filter_whenRouteAttributeAbsent_skipsWarn() {
        // 路由尚未确定（过滤器链前段直接短路）时 route == null，必须安全跳过
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/x"));

        run(filter, exchange);
    }
}
