package uno.acloud.email.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import uno.acloud.common.InternalServiceHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
public class EmailInternalAuthInterceptor implements HandlerInterceptor {

    private final String internalServiceToken;

    public EmailInternalAuthInterceptor(AppProperties appProperties) {
        this.internalServiceToken = appProperties.getInternalServiceToken();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String providedToken = request.getHeader(InternalServiceHeaders.TOKEN_HEADER);
        if (!StringUtils.hasText(internalServiceToken)
                || !StringUtils.hasText(providedToken)
                || !constantTimeEquals(internalServiceToken, providedToken)) {
            log.warn("拒绝邮件服务内部请求：uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
