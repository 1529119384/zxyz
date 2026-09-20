package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.common.permission.TeamPermissionLocalCache;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.config.TeamServiceProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TeamPermissionService#hasPermission} 的本地缓存行为测试。
 * <p>核心断言：<b>同一 (teamId, userId, code) 第二次查询不再发起跨服务 HTTP</b>；
 * 且收到失效后重新回源。前者是这次优化的目的，后者是正确性的底线。</p>
 * <p>⚠️ 这里<b>不用 Mockito 的链式 deep stub</b>：{@code RestClient.post().uri().header()}
 * 的 {@code header(String, String...)} 是 varargs，deep stub 在该签名上会返回 null
 * （实测报 {@code InvalidUseOfMatchers} / NPE）。改为「真实 RestClient + 桩
 * {@link ClientHttpRequestFactory}」，顺带能精确统计真实发出的请求次数。</p>
 */
class TeamPermissionServiceCacheTest {

    /** 统计真实发出的 HTTP 次数、按测试需要返回固定响应体/状态码，并留存最后一次请求供查请求头 */
    static class StubRequestFactory implements ClientHttpRequestFactory {
        final AtomicInteger callCount = new AtomicInteger();
        // 注意：ErrorCode.SUCCESS = 1（不是 0），桩响应必须用 1 才是「成功」
        volatile String responseJson = "{\"code\":1,\"data\":true}";
        /** 07-B-2：默认 200；置为非 2xx 可验证基类的重试与错误归一。 */
        volatile HttpStatus responseStatus = HttpStatus.OK;
        /** 最后一次真实构造的请求 —— 断言「实际发出去的请求头」用。 */
        volatile StubHttpRequest lastRequest;

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            callCount.incrementAndGet();
            StubHttpRequest request = new StubHttpRequest(uri, httpMethod, responseJson, responseStatus);
            lastRequest = request;
            return request;
        }
    }

    static class StubHttpRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final String responseJson;
        private final HttpStatus responseStatus;
        private final HttpHeaders headers = new HttpHeaders();

        StubHttpRequest(URI uri, HttpMethod method, String responseJson, HttpStatus responseStatus) {
            this.uri = uri;
            this.method = method;
            this.responseJson = responseJson;
            this.responseStatus = responseStatus;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return new java.util.HashMap<>();
        }

        @Override
        public ClientHttpResponse execute() {
            return new MockClientHttpResponse(responseJson.getBytes(StandardCharsets.UTF_8), responseStatus) {
                private final ByteArrayInputStream stream =
                        new ByteArrayInputStream(responseJson.getBytes(StandardCharsets.UTF_8));

                @Override
                public HttpStatusCode getStatusCode() {
                    return responseStatus;
                }

                @Override
                public java.io.InputStream getBody() {
                    return stream;
                }

                @Override
                public void close() {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                        // 测试桩，忽略
                    }
                }
            };
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public OutputStream getBody() {
            return new java.io.ByteArrayOutputStream();
        }
    }

    private StubRequestFactory requestFactory;
    private TeamPermissionLocalCache localCache;
    private TeamPermissionService service;

    @BeforeEach
    void setUp() {
        requestFactory = new StubRequestFactory();
        RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
        localCache = new TeamPermissionLocalCache();

        TeamServiceProperties teamServiceProperties = mock(TeamServiceProperties.class);
        when(teamServiceProperties.normalizedBaseUrl()).thenReturn("http://team-service");
        ServiceProperties serviceProperties = mock(ServiceProperties.class);
        when(serviceProperties.getInternalServiceToken()).thenReturn("internal-token");

        // 07-B-2：sourceService / selfServiceKey 改由 AbstractServiceClient 的 @Value 字段注入，
        // 不再是构造参数（纯净单测里它们是 null，internalHeaders 会回退到 internalServiceToken）。
        service = new TeamPermissionService(restClient, new ObjectMapper(), teamServiceProperties,
                serviceProperties, localCache);
    }

    @Test
    void 第二次查询命中本地缓存_不再发起HTTP() {
        assertTrue(service.hasPermission(1L, 2L, "team:member:view"));
        assertTrue(service.hasPermission(1L, 2L, "team:member:view"));

        assertEquals(1, requestFactory.callCount.get(), "第二次应命中本地缓存，不再发 HTTP");
    }

    @Test
    void 不同code各自回源一次() {
        service.hasPermission(1L, 2L, "a");
        service.hasPermission(1L, 2L, "b");
        service.hasPermission(1L, 2L, "a");

        assertEquals(2, requestFactory.callCount.get());
    }

    @Test
    void 失效成员后重新回源() {
        service.hasPermission(1L, 2L, "a");
        localCache.invalidateMember(1L, 2L);
        assertTrue(service.hasPermission(1L, 2L, "a"));

        assertEquals(2, requestFactory.callCount.get());
    }

    @Test
    void 失效团队后重新回源() {
        service.hasPermission(1L, 2L, "a");
        localCache.invalidateTeam(1L);
        assertTrue(service.hasPermission(1L, 2L, "a"));

        assertEquals(2, requestFactory.callCount.get());
    }

    @Test
    void 远程返回无权限时缓存false_且不再回源() {
        requestFactory.responseJson = "{\"code\":1,\"data\":false}";

        assertFalse(service.hasPermission(3L, 4L, "x"));
        assertFalse(service.hasPermission(3L, 4L, "x"));

        assertEquals(1, requestFactory.callCount.get(), "false 也应被缓存，避免重复回源");
    }

    @Test
    void 远程返回非成功码时视为无权限() {
        requestFactory.responseJson = "{\"code\":500,\"data\":false}";

        assertFalse(service.hasPermission(5L, 6L, "y"));
    }

    @Test
    void 不同用户互不共享缓存() {
        service.hasPermission(1L, 2L, "a");
        service.hasPermission(1L, 3L, "a");

        assertEquals(2, requestFactory.callCount.get());
    }

    @Test
    void 请求头带内部鉴权与调用方_并传播X_Request_Id() {
        // 07-B-2：并入 AbstractServiceClient 的核心收益 —— 这套头以前这里只自己写了两件（token、
        // caller），而 X-Request-Id 完全没传，团队服务侧日志因此与 IM 侧的同一次请求对不上。
        // sourceService / selfServiceKey 现在是基类的 @Value 字段（单测不跑 Spring 容器，用
        // ReflectionTestUtils 注入，等价于容器给值）。
        ReflectionTestUtils.setField(service, "sourceService", "im-service");
        ReflectionTestUtils.setField(service, "selfServiceKey", "self-key");
        MDC.put("requestId", "req-abc-123");
        try {
            assertTrue(service.hasPermission(9L, 10L, "team:member:view"));
        } finally {
            MDC.remove("requestId");
        }

        HttpHeaders sent = requestFactory.lastRequest.getHeaders();
        assertEquals("self-key", sent.getFirst(InternalServiceHeaders.TOKEN_HEADER),
                "每服务独立密钥优先于共享 token");
        assertEquals("im-service", sent.getFirst(InternalServiceHeaders.CALLER_SERVICE_HEADER));
        assertEquals("req-abc-123", sent.getFirst(InternalServiceHeaders.REQUEST_ID_HEADER),
                "X-Request-Id 必须由基类从 MDC 传播出去");
    }

    @Test
    void 未配置本服务密钥时回退共享token() {
        assertTrue(service.hasPermission(11L, 12L, "x"));

        assertEquals("internal-token",
                requestFactory.lastRequest.getHeaders().getFirst(InternalServiceHeaders.TOKEN_HEADER));
    }

    @Test
    void 幂等查询遇5xx重试到上限() {
        // 07-B-2：/check 是幂等查询 ⇒ 走基类的 postJsonWithRetry（maxAttempts=3，间隔 500ms）。
        // 这条钉住「重试口径按端点幂等性区分」这一约定：写端点（initialize/grant-role/clear-role）
        // 走的是不重试的 postJson，不要顺手改成 WithRetry。
        requestFactory.responseStatus = HttpStatus.INTERNAL_SERVER_ERROR;

        assertThrows(BusinessException.class, () -> service.hasPermission(21L, 22L, "z"));

        assertEquals(3, requestFactory.callCount.get(), "5xx 应重试到 maxAttempts=3 才放弃");
    }
}
