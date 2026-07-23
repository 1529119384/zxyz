package uno.acloud.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.acloud.common.InternalServiceHeaders;

import java.util.UUID;

/**
 * 为每个请求生成唯一的 X-Request-Id 并注入请求头，供下游服务进行链路追踪。
 * <p>优先使用客户端传入的 X-Request-Id（兼容已有链路），否则生成 UUID。</p>
 */
@Component
public class RequestIdFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    public int getOrder() {
        // 在鉴权之后、路由转发之前执行
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = exchange.getRequest().getHeaders()
                .getFirst(InternalServiceHeaders.REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }

        String finalRequestId = requestId;
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(r -> r.header(InternalServiceHeaders.REQUEST_ID_HEADER, finalRequestId))
                .build();

        log.debug("X-Request-Id: {} {} {}",
                finalRequestId,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getURI().getPath());

        MDC.put("requestId", finalRequestId);
        return chain.filter(mutatedExchange)
                .doFinally(signal -> MDC.remove("requestId"));
    }
}
