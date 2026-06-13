package uno.acloud.gateway.filter;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaHttpMethod;
import cn.dev33.satoken.router.SaRouter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;
import uno.acloud.satoken.AuthServicePort;

import jakarta.annotation.PostConstruct;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway 全局鉴权配置。
 * 所有 /api/** 请求必须携带有效 Sa-Token 登录态，白名单路径除外。
 */
@Configuration
public class SaTokenFilterConfig {

    private final GatewayProperties gatewayProperties;
    private final AuthServicePort authServicePort;
    private Set<String> allowedOriginSet;

    public SaTokenFilterConfig(GatewayProperties gatewayProperties, AuthServicePort authServicePort) {
        this.gatewayProperties = gatewayProperties;
        this.authServicePort = authServicePort;
    }

    @PostConstruct
    void init() {
        allowedOriginSet = Arrays.stream(gatewayProperties.getCors().getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
    }

    @Bean
    public SaReactorFilter getSaReactorFilter() {
        return new SaReactorFilter()
                // 拦截所有 /api/** 路径
                .addInclude("/api/**")
                // 放行健康检查
                .addExclude("/actuator/**")
                // 鉴权逻辑
                .setAuth(obj -> {
                    // 浏览器 CORS 预检不携带登录态，必须先放行给 Gateway CORS 处理。
                    SaRouter.match(SaHttpMethod.OPTIONS).stop();
                    SaRouter.match("/api/**", r -> {
                        // 内部端点拒绝公网访问（抛出 NotPermissionException 使 Sa-Token 返回 HTTP 403）
                        SaRouter.match("/api/internal/**", rr -> {
                            throw new NotPermissionException("internal");
                        });
                        // 白名单路径放行
                        SaRouter.match("/api/public/shares/**").stop();
                        SaRouter.match("/api/users/login").stop();
                        SaRouter.match("/api/users/register").stop();
                        // 其余路径校验登录态
                        authServicePort.checkLogin();
                    });
                })
                // 鉴权异常处理
                .setError(e -> {
                    appendCorsHeadersIfAllowed();
                    if (e instanceof NotPermissionException) {
                        return "{\"code\":4030,\"msg\":\"没有权限\",\"data\":null}";
                    }
                    // 未登录返回 401
                    return "{\"code\":4010,\"msg\":\"未登录或登录已过期\",\"data\":null}";
                });
    }

    private void appendCorsHeadersIfAllowed() {
        String origin = SaHolder.getRequest().getHeader("Origin");
        if (origin == null || !isAllowedOrigin(origin)) {
            return;
        }
        SaHolder.getResponse()
                .setHeader("Access-Control-Allow-Origin", origin)
                .setHeader("Access-Control-Allow-Credentials", "true")
                .setHeader("Access-Control-Expose-Headers", "Authorization")
                .addHeader("Vary", "Origin");
    }

    private boolean isAllowedOrigin(String origin) {
        if (allowedOriginSet.contains("*")) {
            return false;
        }
        return allowedOriginSet.contains(origin);
    }

    /**
     * 请求限流 KeyResolver：基于客户端 IP 地址进行限流。
     * 配合 Gateway 的 RequestRateLimiter 过滤器使用。
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> Mono.just(
                exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown"
        );
    }
}
