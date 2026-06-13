package uno.acloud.im.config;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.satoken.AuthServicePort;

@Component
public class ImTokenAuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    private final AuthServicePort authServicePort;

    public ImTokenAuthService(AuthServicePort authServicePort) {
        this.authServicePort = authServicePort;
    }

    public Long resolveUserIdFromAuthorization(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            throw noLogin();
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return resolveUserIdByToken(token);
    }

    public Long resolveUserIdByHeaderValue(String authorization) {
        return resolveUserIdFromAuthorization(authorization);
    }

    /**
     * 从 Sa-Token cookie 中读取当前登录用户 ID。
     * 前端通过 HttpOnly Cookie 携带 token，不再发送 Authorization header。
     */
    public Long resolveUserIdFromSaToken() {
        try {
            long userId = authServicePort.getCurrentUserId();
            return userId;
        } catch (Exception e) {
            throw noLogin();
        }
    }

    public Long resolveUserIdByToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw noLogin();
        }
        try {
            Long loginId = authServicePort.getLoginIdByToken(token);
            if (loginId == null) {
                throw noLogin();
            }
            return loginId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw noLogin();
        }
    }

    public Long resolveUserIdFromHeaders(HttpHeaders headers) {
        return resolveUserIdFromAuthorization(headers == null ? null : headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    private BusinessException noLogin() {
        return new BusinessException(ErrorCode.NO_LOGIN, "NO_LOGIN");
    }
}
