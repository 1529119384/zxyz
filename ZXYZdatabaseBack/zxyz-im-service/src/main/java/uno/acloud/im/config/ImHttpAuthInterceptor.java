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
import uno.acloud.common.config.InternalAllowList;

@Slf4j
@Component
public class ImHttpAuthInterceptor implements HandlerInterceptor {

    private final ImTokenAuthService tokenAuthService;
    private final InternalAllowList internalAllowList;

    public ImHttpAuthInterceptor(ImTokenAuthService tokenAuthService,
                                 InternalAllowList internalAllowList) {
        this.tokenAuthService = tokenAuthService;
        this.internalAllowList = internalAllowList;
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
        String caller = request.getHeader(InternalServiceHeaders.CALLER_SERVICE_HEADER);
        if (!internalAllowList.verify(caller, providedToken)) {
            log.warn("Rejected IM internal request: uri={}, caller={}, remoteAddr={}",
                    request.getRequestURI(), caller, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
