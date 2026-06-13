package uno.acloud.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 记录命中兜底路由（fallback）的请求，便于排查未匹配的 API 路径。
 * <p>
 * 兜底路由 {@code /api/** → project-service} 是为了兼容未显式注册的路径，
 * 此过滤器在请求完成后输出 WARN 日志，提醒开发者检查是否有遗漏的路由配置。
 */
@Component
public class FallbackLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(FallbackLoggingFilter.class);

    private static final String FALLBACK_ROUTE_ID = "fallback";

    @Override
    public int getOrder() {
        // 在路由确定之后、实际转发之前执行
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null && FALLBACK_ROUTE_ID.equals(route.getId())) {
                String path = exchange.getRequest().getURI().getPath();
                int status = exchange.getResponse().getStatusCode() != null
                        ? exchange.getResponse().getStatusCode().value() : 0;
                log.warn("Gateway 兗底路由命中: {} {} → status={}",
                        exchange.getRequest().getMethod(), path, status);
            }
        }));
    }
}
