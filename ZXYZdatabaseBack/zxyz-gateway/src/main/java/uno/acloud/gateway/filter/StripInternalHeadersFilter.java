package uno.acloud.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.acloud.common.InternalServiceHeaders;

/**
 * 剥离外部伪造的内部服务头 GlobalFilter。
 * <p>服务间鉴权头（{@code X-Internal-Service-Token} / {@code X-Internal-Caller-Service}）只应由
 * <b>服务间直连</b>产生（各服务 *ServiceClient / AbstractServiceClient），且经 NACOS + 独立端口，不经网关。
 * 外部客户端（浏览器/脚本）经公网到达网关时应<b>携带不了</b>这两个头——尤其过渡期只靠单 token
 * 比对的模式，伪造来源标识可绕过内部端点防线。</p>
 * <p>本过滤器在转发前<b>无条件剥离</b>这两个内部头，使下游服务只会看到「网关自己注入」的合法身份
 * （见 application.yml 中 admin 桥接路由的 AddRequestHeader 注入），以中间件层面切断伪造来源直达。
 * 不影响 {@code X-Request-Id} 透传。</p>
 * <p>执行顺序：在 {@link ClientIpFilter}（HIGHEST_PRECEDENCE）之后、所有业务过滤与负载均衡转发之前，
 * 抢占剥离原始头；受网关 {@code AddRequestHeader} 的 admin 桥接会随后补回内部身份。</p>
 */
@Component
public class StripInternalHeadersFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(StripInternalHeadersFilter.class);

    @Override
    public int getOrder() {
        // 紧跟 ClientIpFilter（HIGHEST_PRECEDENCE）之后，早于 SaReactorFilter 鉴权与路由转发
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    boolean removed = h.remove(InternalServiceHeaders.TOKEN_HEADER) != null;
                    boolean removedCaller = h.remove(InternalServiceHeaders.CALLER_SERVICE_HEADER) != null;
                    if ((removed || removedCaller) && log.isDebugEnabled()) {
                        log.debug("stripped forged internal headers from inbound gateway request: uri={}",
                                exchange.getRequest().getURI());
                    }
                }))
                .build();
        return chain.filter(mutated);
    }
}