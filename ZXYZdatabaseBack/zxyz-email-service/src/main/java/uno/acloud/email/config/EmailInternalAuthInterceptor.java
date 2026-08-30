package uno.acloud.email.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.common.config.InternalAllowList;

@Slf4j
@Component
public class EmailInternalAuthInterceptor implements HandlerInterceptor {

    private final InternalAllowList internalAllowList;

    public EmailInternalAuthInterceptor(InternalAllowList internalAllowList) {
        this.internalAllowList = internalAllowList;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String providedToken = request.getHeader(InternalServiceHeaders.TOKEN_HEADER);
        String caller = request.getHeader(InternalServiceHeaders.CALLER_SERVICE_HEADER);
        if (!internalAllowList.verify(caller, providedToken)) {
            log.warn("拒绝邮件服务内部请求：uri={}, caller={}, remoteAddr={}",
                    request.getRequestURI(), caller, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }
}
