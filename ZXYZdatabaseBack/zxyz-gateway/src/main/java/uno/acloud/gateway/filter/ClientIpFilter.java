package uno.acloud.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * 真实客户端 IP 解析 GlobalFilter。
 * <p>在转发前解析真实客户端 IP：
 * <ul>
 *   <li>无条件删除客户端自传的 {@code X-Real-IP} 与 {@code X-Forwarded-For}，防止伪造源 IP；</li>
 *   <li>仅在配置了可信代理（{@code app.gateway.trusted-proxies}）时，从 XFF 取最后一跳可信值并
 *       <b>覆盖式</b>写 {@code X-Real-IP}，供限流器与下游安全逻辑读取；</li>
 *   <li>功能默认关闭：未配置可信代理时不写 X-Real-IP，限流回落远程地址（nginx IP），安全且不泄露。</li>
 * </ul></p>
 * <p>必须以高于内置 {@code ForwardedHeaderFilter}（{@code HIGHEST_PRECEDENCE + 1}）的优先级运行，
 * 抢先捕获原始 XFF 后再交给后续请求处理。</p>
 */
@Component
public class ClientIpFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ClientIpFilter.class);

    private final GatewayProperties gatewayProperties;

    public ClientIpFilter(GatewayProperties gatewayProperties) {
        this.gatewayProperties = gatewayProperties;
    }

    @Override
    public int getOrder() {
        // 早于内置 ForwardedHeaderFilter（HIGHEST_PRECEDENCE + 1）与 RequestRateLimiter（order 0）
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String remote = remoteAddress(exchange);
        String xff = exchange.getRequest().getHeaders().getFirst(ClientIpResolver.X_FORWARDED_FOR_HEADER);

        // 覆盖式写 X-Real-IP：先清除客户端自传的 X-Real-IP / X-Forwarded-For，再写入解析结果，
        // 确保下游（限流器、安全逻辑、业务服务）只信任网关解析出的真实源 IP
        String clientIp = ClientIpResolver.resolve(remote, xff, gatewayProperties.getGateway().trustedProxySet());

        var mutated = exchange.mutate()
                .request(r -> r.headers(h -> {
                    h.remove(ClientIpResolver.X_FORWARDED_FOR_HEADER);
                    h.remove(ClientIpResolver.X_REAL_IP_HEADER);
                    h.set(ClientIpResolver.X_REAL_IP_HEADER, clientIp);
                }))
                .build();

        if (log.isDebugEnabled()) {
            log.debug("real client ip resolved: remote={} xff={} -> X-Real-IP={}",
                    remote, xff, clientIp);
        }
        return chain.filter(mutated);
    }

    private String remoteAddress(ServerWebExchange exchange) {
        InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
        return addr != null && addr.getAddress() != null
                ? addr.getAddress().getHostAddress()
                : "unknown";
    }
}