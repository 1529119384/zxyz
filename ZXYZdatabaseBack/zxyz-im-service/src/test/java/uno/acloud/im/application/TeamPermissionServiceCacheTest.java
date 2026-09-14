package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import uno.acloud.common.permission.TeamPermissionLocalCache;
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

    /** 统计真实发出的 HTTP 次数，并按测试需要返回固定响应体 */
    static class StubRequestFactory implements ClientHttpRequestFactory {
        final AtomicInteger callCount = new AtomicInteger();
        // 注意：ErrorCode.SUCCESS = 1（不是 0），桩响应必须用 1 才是「成功」
        volatile String responseJson = "{\"code\":1,\"data\":true}";

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
            callCount.incrementAndGet();
            return new StubHttpRequest(uri, httpMethod, responseJson);
        }
    }

    static class StubHttpRequest implements ClientHttpRequest {
        private final URI uri;
        private final HttpMethod method;
        private final String responseJson;
        private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();

        StubHttpRequest(URI uri, HttpMethod method, String responseJson) {
            this.uri = uri;
            this.method = method;
            this.responseJson = responseJson;
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
            return new MockClientHttpResponse(responseJson.getBytes(StandardCharsets.UTF_8), HttpStatus.OK) {
                private final ByteArrayInputStream stream =
                        new ByteArrayInputStream(responseJson.getBytes(StandardCharsets.UTF_8));

                @Override
                public HttpStatusCode getStatusCode() {
                    return HttpStatus.OK;
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

        service = new TeamPermissionService(restClient, new ObjectMapper(), teamServiceProperties,
                serviceProperties, localCache, "im-service", "self-key");
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
}
