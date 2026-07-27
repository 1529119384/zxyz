package uno.acloud.user.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * 认证 Cookie 工具类。
 * 登录时设置 HttpOnly cookie（主认证）。
 */
@Component
public class CookieHelper {

    private static final String AUTH_COOKIE_NAME = "satoken";
    private final ServiceProperties serviceProperties;

    public CookieHelper(ServiceProperties serviceProperties) {
        this.serviceProperties = serviceProperties;
    }

    /**
     * 设置认证 cookies。
     * @param response HTTP 响应
     * @param token    登录 token
     * @param maxAge   Cookie 最大存活时间（秒）
     */
    public void setAuthCookies(HttpServletResponse response, String token, int maxAge) {
        Cookie authCookie = new Cookie(AUTH_COOKIE_NAME, token);
        authCookie.setHttpOnly(true);
        authCookie.setPath("/");
        authCookie.setMaxAge(maxAge);
        authCookie.setSecure(serviceProperties.getAuth().isSecure());
        authCookie.setAttribute("SameSite", "Lax");
        String cookieDomain = serviceProperties.getAuth().getDomain();
        if (cookieDomain != null && !cookieDomain.isBlank()) {
            authCookie.setDomain(cookieDomain);
        }
        response.addCookie(authCookie);
    }

    /**
     * 清除认证 cookies（登出时调用）。
     * @param response HTTP 响应
     */
    public void clearAuthCookies(HttpServletResponse response) {
        Cookie authCookie = new Cookie(AUTH_COOKIE_NAME, "");
        authCookie.setHttpOnly(true);
        authCookie.setPath("/");
        authCookie.setMaxAge(0);
        authCookie.setSecure(serviceProperties.getAuth().isSecure());
        String clearDomain = serviceProperties.getAuth().getDomain();
        if (clearDomain != null && !clearDomain.isBlank()) {
            authCookie.setDomain(clearDomain);
        }
        response.addCookie(authCookie);
    }
}
