package uno.acloud.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link RequestIdFilter} 单元测试。
 *
 * <p>核心防回归点有两个：① 请求头 {@code X-Request-Id} 必须真的被写进 MDC，
 * 否则 {@code AbstractServiceClient} 透传时拿不到值、跨服务链路在第一跳就断；
 * ② 请求结束后 MDC 必须被清空 —— 容器线程复用，残留的 ID 会被下一个请求"继承"，
 * 一个错误的 ID 比没有 ID 更容易误导排查。</p>
 */
class RequestIdFilterTest {

    /** 与 {@code InternalServiceHeaders.REQUEST_ID_HEADER} 同值；此处写死是为了让本用例能独立发现该常量被改名。 */
    private static final String HEADER = "X-Request-Id";

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdcAfterEach() {
        MDC.clear();
    }

    @Test
    void doFilter_restoresMdcFromHeaderAndEchoesItBack() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturing(mdcDuringChain));

        assertEquals("abc123", mdcDuringChain.get(), "过滤器链执行期间 MDC 必须已有 requestId");
        assertEquals("abc123", response.getHeader(HEADER), "响应头应回写同一个 requestId，便于对上服务端日志");
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "请求结束后必须清空 MDC");
    }

    @Test
    void doFilter_generatesRequestIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturing(mdcDuringChain));

        String requestId = mdcDuringChain.get();
        assertNotNull(requestId, "缺失请求头时必须兜底生成 requestId（直连服务/内部探针场景）");
        assertFalse(requestId.isBlank());
        assertEquals(32, requestId.length(), "兜底生成的 UUID 应去掉连字符");
        assertEquals(requestId, response.getHeader(HEADER), "生成的 ID 也要回写响应头");
    }

    @Test
    void doFilter_generatesRequestIdWhenHeaderBlank() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturing(mdcDuringChain));

        assertFalse(mdcDuringChain.get().isBlank(), "空白请求头应视为缺失，不能把空白 ID 写进 MDC");
    }

    @Test
    void doFilter_overwritesStaleMdcFromReusedThread() throws Exception {
        // 模拟"线程被复用且上一个请求忘了清理"的最坏情况。
        MDC.put(RequestIdFilter.MDC_KEY, "stale-from-previous-request");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "fresh-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, capturing(mdcDuringChain));

        assertEquals("fresh-id", mdcDuringChain.get(), "新请求必须覆盖残留值，否则会串号");
        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
    }

    @Test
    void doFilter_clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HEADER, "boom-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThrows(ServletException.class, () -> filter.doFilter(request, response, failingChain));
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "链路抛异常时也必须清空 MDC（finally 分支）");
    }

    /** 构造一个只在执行期间记录 MDC 的过滤器链。 */
    private static FilterChain capturing(AtomicReference<String> sink) {
        return (req, res) -> sink.set(MDC.get(RequestIdFilter.MDC_KEY));
    }
}
