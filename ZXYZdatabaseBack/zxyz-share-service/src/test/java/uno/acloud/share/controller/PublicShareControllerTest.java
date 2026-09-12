package uno.acloud.share.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uno.acloud.share.controller.support.ShareCookieManager;
import uno.acloud.share.dto.ShareAccessRequest;
import uno.acloud.share.service.SharePort;
import uno.acloud.share.service.impl.ShareAccessRateLimiter;
import uno.acloud.share.service.model.ShareVerifyResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublicShareController} 的「真实客户端 IP」防回归测试（审计 12-P0-2）。
 * <p>改造前该端点用 {@code getRemoteAddr()}（经网关后恒为容器 IP）当限流键，
 * 于是对某个 shareKey 的失败计数落进全局单桶——任何人循环输错提取码即可把
 * <b>所有用户</b>对该分享的验证一起锁死。</p>
 */
@ExtendWith(MockitoExtension.class)
class PublicShareControllerTest {

    /** 模拟网关/nginx 容器地址：所有真实客户端在服务侧看到的都是这一个值。 */
    private static final String GATEWAY_CONTAINER_IP = "172.18.0.5";
    private static final String SHARE_KEY = "share-key-1";

    @Mock
    private SharePort shareService;
    @Mock
    private ShareCookieManager shareCookieManager;
    @Mock
    private ShareAccessRateLimiter rateLimiter;

    private PublicShareController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicShareController(shareService, shareCookieManager, rateLimiter);
    }

    private MockHttpServletRequest requestBehindGateway(String realIp) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(GATEWAY_CONTAINER_IP);
        if (realIp != null) {
            request.addHeader("X-Real-IP", realIp);
        }
        return request;
    }

    private void verifyShare(MockHttpServletRequest request) {
        controller.verifyShare(SHARE_KEY, new ShareAccessRequest(), request, new MockHttpServletResponse());
    }

    @Test
    void verifyShare_usesGatewayRealIpForRateLimit_notContainerIp() {
        when(shareService.verifyShare(any(), any())).thenReturn(ShareVerifyResult.passedWithoutNewToken());

        verifyShare(requestBehindGateway("203.0.113.7"));

        verify(rateLimiter).checkAndIncrement(SHARE_KEY, "203.0.113.7");
    }

    @Test
    void verifyShare_fallsBackToRemoteAddrWhenGatewayHeaderMissing() {
        when(shareService.verifyShare(any(), any())).thenReturn(ShareVerifyResult.passedWithoutNewToken());

        verifyShare(requestBehindGateway(null));

        verify(rateLimiter).checkAndIncrement(SHARE_KEY, GATEWAY_CONTAINER_IP);
    }

    @Test
    void verifyShare_twoClientsBehindSameGateway_getSeparateRateLimitBuckets() {
        when(shareService.verifyShare(any(), any())).thenReturn(ShareVerifyResult.passedWithoutNewToken());

        verifyShare(requestBehindGateway("203.0.113.7"));
        verifyShare(requestBehindGateway("198.51.100.9"));

        ArgumentCaptor<String> ipCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, times(2)).checkAndIncrement(eq(SHARE_KEY), ipCaptor.capture());

        List<String> capturedIps = ipCaptor.getAllValues();
        assertEquals(List.of("203.0.113.7", "198.51.100.9"), capturedIps);
        assertNotEquals(capturedIps.get(0), capturedIps.get(1),
                "两个真实客户端必须落到不同限流键；若相同即说明退化成全局单桶");
    }

    @Test
    void verifyShare_successfulVerificationResetsCounter() {
        when(shareService.verifyShare(any(), any())).thenReturn(ShareVerifyResult.passedWithoutNewToken());

        verifyShare(requestBehindGateway("203.0.113.7"));

        verify(rateLimiter).reset(SHARE_KEY);
    }
}
