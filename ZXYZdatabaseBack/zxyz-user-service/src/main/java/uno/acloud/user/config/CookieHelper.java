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
     *
     * <p><b>双写事实与权威归属（2026-10-10 ISSUE/52）：</b>
     * Sa-Token 框架在 {@code sa-token.is-read-cookie=true}（默认开启）下，
     * {@code StpUtil.login} 时会自动写一枚<b>非 HttpOnly 的会话级</b> satoken cookie；
     * 本方法是<b>权威写入</b>（HttpOnly + SameSite/Secure/Domain/MaxAge 属性完整），
     * 依赖其在本方法返回后覆盖框架默认值（调用点 UserController.login 在
     * {@code authService.login()} —— 即 StpUtil.login —— 之后才调用本方法）。
     * <b>若调整调用顺序，须重新核对覆盖关系</b>，否则客户端将拿到框架的短属性 cookie，
     * HttpOnly 防护静默失效。</p>
     *
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
