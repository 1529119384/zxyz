package uno.acloud.im.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import uno.acloud.common.InternalServiceHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@Component
public class ImHttpAuthInterceptor implements HandlerInterceptor {

    private final ImTokenAuthService tokenAuthService;
    private final String internalServiceToken;

    public ImHttpAuthInterceptor(ImTokenAuthService tokenAuthService,
                                 ServiceProperties serviceProperties) {
        this.tokenAuthService = tokenAuthService;
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (isInternalRequest(request)) {
            return verifyInternalToken(request, response);
        }
        Long userId;
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authHeader)) {
            userId = tokenAuthService.resolveUserIdByHeaderValue(authHeader);
        } else {
            userId = tokenAuthService.resolveUserIdFromSaToken();
        }
        request.setAttribute(ImAuthContext.USER_ID_ATTRIBUTE, userId);
        return true;
    }

    private boolean isInternalRequest(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().startsWith("/api/internal/im/");
    }

    private boolean verifyInternalToken(HttpServletRequest request, HttpServletResponse response) {
        String providedToken = request.getHeader(InternalServiceHeaders.TOKEN_HEADER);
        if (!StringUtils.hasText(internalServiceToken) || !StringUtils.hasText(providedToken)
                || !constantTimeEquals(internalServiceToken, providedToken)) {
            log.warn("Rejected IM internal request: uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }
}
