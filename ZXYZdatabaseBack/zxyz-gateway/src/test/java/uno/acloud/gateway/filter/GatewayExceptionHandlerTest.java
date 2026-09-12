package uno.acloud.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import uno.acloud.common.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GatewayExceptionHandler} 决定前端在网关层出错时**收到 JSON 而不是 Spring 默认 HTML 错误页**。
 * 因此逐条钉死：异常类型 → 业务码 → HTTP 状态码 → 响应体 → CORS 头。
 */
class GatewayExceptionHandlerTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    private static GatewayExceptionHandler handler() {
        return handler(ALLOWED_ORIGIN);
    }

    private static GatewayExceptionHandler handler(String allowedOrigins) {
        return new GatewayExceptionHandler(new ObjectMapper(), allowedOrigins);
    }

    /** 执行处理器并返回写出的响应体。 */
    private static String handle(GatewayExceptionHandler handler,
                                 MockServerWebExchange exchange,
                                 Throwable ex) {
        handler.handle(exchange, ex).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    private static MockServerWebExchange exchangeWithOrigin(String origin) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/x");
        if (origin != null) {
            builder = builder.header("Origin", origin);
        }
        return MockServerWebExchange.from(builder.build());
    }

    /** 断言"异常 → 业务码/文案/HTTP 状态/JSON 响应体"这一整条链路自洽。 */
    private void assertMaps(HttpStatus thrownStatus, int expectedCode, String expectedMsg) {
        MockServerWebExchange exchange = exchangeWithOrigin(null);

        String body = handle(handler(), exchange,
                new ResponseStatusException(thrownStatus, "boom"));

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(ErrorCode.resolveHttpStatus(expectedCode));
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body).contains("\"code\":" + expectedCode).contains(expectedMsg);
    }

    @Test
    void getOrder_isHighestPrecedenceToBeatDefaultErrorHandler() {
        assertThat(handler().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void handle_whenUnauthorized_mapsToNoLogin() {
        assertMaps(HttpStatus.UNAUTHORIZED, ErrorCode.NO_LOGIN, "未登录或登录已过期");
    }

    @Test
    void handle_whenForbidden_mapsToNoPermission() {
        assertMaps(HttpStatus.FORBIDDEN, ErrorCode.NO_PERMISSION, "没有权限");
    }

    @Test
    void handle_whenNotFound_mapsToNotFound() {
        assertMaps(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "资源不存在");
    }

    @Test
    void handle_whenServiceUnavailable_saysRetryLater() {
        // 文案表明"暂时不可用"，但 HTTP 状态码由 ErrorCode.resolveHttpStatus(5000) 统一映射为 500
        //（而非 503）—— 这是当前实现的既定行为，改前先确认前端/nginx 是否依赖 503。
        assertMaps(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SYSTEM_ERROR, "服务暂时不可用，请稍后重试");
    }

    @Test
    void handle_whenOtherResponseStatus_fallsBackToGenericMessage() {
        assertMaps(HttpStatus.I_AM_A_TEAPOT, ErrorCode.SYSTEM_ERROR, "服务器异常，请稍后重试");
    }

    @Test
    void handle_whenNotResponseStatusException_mapsToSystemError() {
        MockServerWebExchange exchange = exchangeWithOrigin(null);

        String body = handle(handler(), exchange, new IllegalStateException("Connection refused"));

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(ErrorCode.resolveHttpStatus(ErrorCode.SYSTEM_ERROR));
        assertThat(body).contains("\"code\":5000").contains("服务器异常，请稍后重试");
    }

    @Test
    void handle_whenResponseAlreadyCommitted_propagatesOriginalError() {
        MockServerWebExchange exchange = exchangeWithOrigin(null);
        exchange.getResponse().setComplete().block();
        assertThat(exchange.getResponse().isCommitted()).isTrue();

        // 响应已提交时无法再改写，只能把原异常继续抛出去，不能假装成功
        assertThatThrownBy(() -> handler().handle(exchange, new IllegalStateException("late")).block())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handle_appendsCorsHeadersWhenOriginAllowed() {
        MockServerWebExchange exchange = exchangeWithOrigin(ALLOWED_ORIGIN);

        handle(handler(), exchange, new IllegalStateException("x"));

        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo(ALLOWED_ORIGIN);
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Credentials"))
                .isEqualTo("true");
        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Expose-Headers"))
                .isEqualTo("Authorization");
        assertThat(exchange.getResponse().getHeaders().get("Vary")).contains("Origin");
    }

    @Test
    void handle_omitsCorsHeadersWhenOriginNotAllowed() {
        MockServerWebExchange exchange = exchangeWithOrigin("http://evil.com");

        handle(handler(), exchange, new IllegalStateException("x"));

        assertThat(exchange.getResponse().getHeaders().containsKey("Access-Control-Allow-Origin"))
                .isFalse();
    }

    @Test
    void handle_omitsCorsHeadersWhenOriginHeaderAbsent() {
        MockServerWebExchange exchange = exchangeWithOrigin(null);

        handle(handler(), exchange, new IllegalStateException("x"));

        assertThat(exchange.getResponse().getHeaders().containsKey("Access-Control-Allow-Origin"))
                .isFalse();
    }

    @Test
    void handle_whenWildcardConfigured_rejectsEveryOrigin() {
        MockServerWebExchange exchange = exchangeWithOrigin(ALLOWED_ORIGIN);

        // 与 Access-Control-Allow-Credentials: true 不兼容 ⇒ 配成 * 时全拒，防误配把凭据放开
        handle(handler("*"), exchange, new IllegalStateException("x"));

        assertThat(exchange.getResponse().getHeaders().containsKey("Access-Control-Allow-Origin"))
                .isFalse();
    }

    @Test
    void handle_parsesCommaSeparatedOriginsWithSpaces() {
        MockServerWebExchange exchange = exchangeWithOrigin("http://b.com");

        handle(handler("http://a.com, http://b.com"), exchange, new IllegalStateException("x"));

        assertThat(exchange.getResponse().getHeaders().getFirst("Access-Control-Allow-Origin"))
                .isEqualTo("http://b.com");
    }
}
