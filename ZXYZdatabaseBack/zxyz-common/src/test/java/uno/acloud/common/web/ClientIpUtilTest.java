package uno.acloud.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ClientIpUtil} 单元测试。
 * <p>核心防回归点：经网关后 {@code getRemoteAddr()} 恒为网关/nginx 容器 IP，
 * 必须优先采用网关注入的 {@code X-Real-IP}，否则按 IP 的限流会退化为全局单桶（审计 12-P0-2）。</p>
 */
class ClientIpUtilTest {

    /** 模拟网关/nginx 容器地址——所有真实客户端在服务侧看到的都是这一个值。 */
    private static final String GATEWAY_CONTAINER_IP = "172.18.0.5";

    private static MockHttpServletRequest requestWithRemoteAddr(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void resolve_prefersGatewayInjectedRealIpOverRemoteAddr() {
        MockHttpServletRequest request = requestWithRemoteAddr(GATEWAY_CONTAINER_IP);
        request.addHeader(ClientIpUtil.X_REAL_IP_HEADER, "203.0.113.7");

        assertEquals("203.0.113.7", ClientIpUtil.resolve(request));
    }

    @Test
    void resolve_trimsSurroundingWhitespaceOfRealIp() {
        MockHttpServletRequest request = requestWithRemoteAddr(GATEWAY_CONTAINER_IP);
        request.addHeader(ClientIpUtil.X_REAL_IP_HEADER, "  203.0.113.7  ");

        assertEquals("203.0.113.7", ClientIpUtil.resolve(request));
    }

    @Test
    void resolve_fallsBackToRemoteAddrWhenHeaderAbsent() {
        assertEquals(GATEWAY_CONTAINER_IP, ClientIpUtil.resolve(requestWithRemoteAddr(GATEWAY_CONTAINER_IP)));
    }

    @Test
    void resolve_fallsBackToRemoteAddrWhenHeaderBlank() {
        MockHttpServletRequest request = requestWithRemoteAddr(GATEWAY_CONTAINER_IP);
        request.addHeader(ClientIpUtil.X_REAL_IP_HEADER, "   ");

        assertEquals(GATEWAY_CONTAINER_IP, ClientIpUtil.resolve(request));
    }

    @Test
    void resolve_ignoresHeaderContainingComma_asForgedInput() {
        MockHttpServletRequest request = requestWithRemoteAddr(GATEWAY_CONTAINER_IP);
        request.addHeader(ClientIpUtil.X_REAL_IP_HEADER, "203.0.113.7, 198.51.100.9");

        assertEquals(GATEWAY_CONTAINER_IP, ClientIpUtil.resolve(request));
    }

    @Test
    void resolve_ignoresHeaderContainingSpace_asForgedInput() {
        MockHttpServletRequest request = requestWithRemoteAddr(GATEWAY_CONTAINER_IP);
        request.addHeader(ClientIpUtil.X_REAL_IP_HEADER, "203.0.113.7 198.51.100.9");

        assertEquals(GATEWAY_CONTAINER_IP, ClientIpUtil.resolve(request));
    }

    @Test
    void resolve_nullRequest_returnsUnknown() {
        assertEquals(ClientIpUtil.UNKNOWN, ClientIpUtil.resolve(null));
    }

    @Test
    void resolve_neitherHeaderNorRemoteAddr_returnsUnknownInsteadOfNull() {
        assertEquals(ClientIpUtil.UNKNOWN, ClientIpUtil.resolve(requestWithRemoteAddr(null)));
    }

    @Test
    void resolve_emptyRemoteAddr_returnsUnknown() {
        assertEquals(ClientIpUtil.UNKNOWN, ClientIpUtil.resolve(requestWithRemoteAddr("  ")));
    }
}
