package uno.acloud.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gateway 通用异常处理器。
 * <p>
 * 将所有异常（包括下游服务不可用、路由失败等）统一转换为 JSON 格式的
 * {@code Result} 响应体，避免前端收到 Spring 默认的 HTML 错误页面。
 * <p>
 * 注册为 Spring Bean 并设置最高优先级（{@code Ordered.HIGHEST_PRECEDENCE}），
 * 确保在 Spring Cloud Gateway 默认的 {@code DefaultErrorWebExceptionHandler} 之前执行。
 */
@Component
public class GatewayExceptionHandler implements ErrorWebExceptionHandler, Ordered {

    private final ObjectMapper objectMapper;

    /** 允许的 CORS 来源列表，与 SaTokenFilterConfig 保持一致 */
    private final Set<String> allowedOrigins;

    // 通过构造函数注入 Spring 管理的 ObjectMapper，避免自行创建导致配置不一致
    public GatewayExceptionHandler(
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:4173}")
            String allowedOriginsConfig) {
        this.objectMapper = objectMapper;
        this.allowedOrigins = Arrays.stream(allowedOriginsConfig.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public int getOrder() {
        // 最高优先级，确保本处理器先于默认处理器执行
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        // 如果响应已经提交（比如部分数据已写出），则无法再修改，直接跳过
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        // 根据异常类型映射错误码和提示信息
        int code;
        String msg;

        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.valueOf(rse.getStatusCode().value());
            switch (status) {
                case UNAUTHORIZED -> {
                    code = ErrorCode.NO_LOGIN;       // 4010
                    msg = "未登录或登录已过期";
                }
                case FORBIDDEN -> {
                    code = ErrorCode.NO_PERMISSION;   // 4030
                    msg = "没有权限";
                }
                case NOT_FOUND -> {
                    code = ErrorCode.NOT_FOUND;       // 4040
                    msg = "资源不存在";
                }
                case SERVICE_UNAVAILABLE -> {
                    code = ErrorCode.SYSTEM_ERROR;    // 5000
                    msg = "服务暂时不可用，请稍后重试";
                }
                default -> {
                    code = ErrorCode.SYSTEM_ERROR;    // 5000
                    msg = "服务器异常，请稍后重试";
                }
            }
        } else {
            // 非 ResponseStatusException 的所有异常统一视为系统错误
            code = ErrorCode.SYSTEM_ERROR;            // 5000
            msg = "服务器异常，请稍后重试";
        }

        // 构造统一响应体
        Result<?> result = Result.error(code, msg);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            // Jackson 序列化 Result 理论上不会失败，此处兜底；对 msg 做基本转义防止 JSON 注入
            String safeMsg = msg.replace("\\", "\\\\").replace("\"", "\\\"");
            body = ("{\"code\":" + code + ",\"msg\":\"" + safeMsg + "\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }

        // 设置 CORS headers，复用 SaTokenFilterConfig 中的逻辑
        appendCorsHeaders(exchange);

        // 写出响应（根据业务错误码映射正确的 HTTP 状态码）
        response.setStatusCode(ErrorCode.resolveHttpStatus(code));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 向响应头追加 CORS 允许字段（仅当 Origin 在白名单内时生效）。
     * <p>
     * 逻辑与 {@link SaTokenFilterConfig#appendCorsHeadersIfAllowed()} 保持一致，
     * 区别在于此处操作的是 {@link ServerHttpResponse}，而非 Sa-Token 的响应上下文。
     */
    private void appendCorsHeaders(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getFirst("Origin");
        if (origin == null || !isAllowedOrigin(origin)) {
            return;
        }
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set("Access-Control-Allow-Origin", origin);
        response.getHeaders().set("Access-Control-Allow-Credentials", "true");
        response.getHeaders().set("Access-Control-Expose-Headers", "Authorization");
        response.getHeaders().add("Vary", "Origin");
    }

    private boolean isAllowedOrigin(String origin) {
        if (allowedOrigins.contains("*")) {
            return false;
        }
        return allowedOrigins.contains(origin);
    }
}
