package uno.acloud.file.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ActingUserIdResolver} 单测（P1-1 修复的接收端钉子）。
 *
 * <p>内部链路不存在用户会话，操作者身份只能来自 {@code X-Acting-User-Id} 请求头；
 * 头缺失/空白/非法 Long 都必须得到明确错误码，<b>绝不</b>回退到 StpUtil ——
 * 回退即把「无主内部请求」误挂到错误用户名下（越权风险）。</p>
 */
class ActingUserIdResolverTest {

    private final ActingUserIdResolver resolver = new ActingUserIdResolver();

    @Test
    void requireActingUserId_parsesValidHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ActingUserIdResolver.ACTING_USER_ID_HEADER, "42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals(42L, resolver.requireActingUserId());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requireActingUserId_trimsWhitespaceAroundValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ActingUserIdResolver.ACTING_USER_ID_HEADER, " 42 ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals(42L, resolver.requireActingUserId());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requireActingUserId_rejectsMissingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            BusinessException ex = assertThrows(BusinessException.class, resolver::requireActingUserId);
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
            assertTrue(ex.getMessage().contains("X-Acting-User-Id"),
                    "错误信息必须指明缺失的头名，便于调用方排查；实际：" + ex.getMessage());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requireActingUserId_rejectsBlankHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ActingUserIdResolver.ACTING_USER_ID_HEADER, "   ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            BusinessException ex = assertThrows(BusinessException.class, resolver::requireActingUserId);
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requireActingUserId_rejectsNonNumericValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 攻击面：恶意/损坏的头值必须被拒绝而不是误解析
        request.addHeader(ActingUserIdResolver.ACTING_USER_ID_HEADER, "<script>alert(1)</script>");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            BusinessException ex = assertThrows(BusinessException.class, resolver::requireActingUserId);
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
            assertFalse(ex.getMessage().contains("script"),
                    "异常消息不得回显恶意头值；实际：" + ex.getMessage());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requireActingUserId_rejectsWhenNoRequestContext() {
        // RequestContextHolder 为空（非 HTTP 线程/异步任务误调）也必须走明确拒绝
        assertThrows(BusinessException.class, resolver::requireActingUserId);
    }
}
