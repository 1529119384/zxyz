package uno.acloud.share.controller.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.share.common.ShareTokenCodec;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.infrastructure.entity.Share;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

@Component
public class ShareCookieManager {
    private static final String COOKIE_PREFIX = "share_access_";

    /** 时间基准与写入端（ShareManager）/ 过期判定（ShareStatusCalculator）同源，见 ShareTimeSource（审计 D3）。 */
    private final ShareTimeSource timeSource;

    public ShareCookieManager(ShareTimeSource timeSource) {
        this.timeSource = timeSource;
    }

    @Nullable
    public String resolveAccessToken(String shareKey, @Nullable HttpServletRequest request) {
        if (StringUtils.isBlank(shareKey) || request == null || request.getCookies() == null) {
            return null;
        }
        String cookieName = buildCookieName(shareKey);
        for (Cookie cookie : request.getCookies()) {
            if (Objects.equals(cookieName, cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void writeAccessToken(String shareKey,
                                 String accessToken,
                                 LocalDateTime expireTime,
                                 HttpServletResponse response) {
        if (StringUtils.isBlank(shareKey) || StringUtils.isBlank(accessToken) || response == null) {
            return;
        }
        // 使用 ResponseCookie 替代原生 Cookie，添加 SameSite 和 Secure 属性防止 CSRF
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(buildCookieName(shareKey), accessToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/");
        if (expireTime != null) {
            builder.maxAge(resolveCookieMaxAge(expireTime));
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }

    private String buildCookieName(String shareKey) {
        return COOKIE_PREFIX + shareKey;
    }

    /**
     * 签发访问令牌（v3：把**签发时刻**也纳入签名输入）。
     *
     * <p>格式与签名实现在 {@link ShareTokenCodec}；本类只负责「时间从哪儿来」——
     * 取注入的 {@link ShareTimeSource}，与 Cookie 的 {@code Max-Age} 用同一个基准（审计 D3）。
     * 签发时刻随令牌一起下发，校验端才能复算出同一个摘要。</p>
     */
    public String buildAccessToken(Share share, String cookieSecret) {
        return ShareTokenCodec.buildToken(share, cookieSecret, timeSource.now());
    }

    /**
     * 校验访问令牌（v3）。
     *
     * <p>常量时间比较等细节见 {@link ShareTokenCodec#verify}。令牌版本不是 v3
     * （例如升级前签发的 v2 Cookie）一律判为不通过。</p>
     */
    public boolean verifyAccessToken(Share share, String cookieSecret, String accessToken) {
        return ShareTokenCodec.verify(share, cookieSecret, accessToken);
    }

    private int resolveCookieMaxAge(LocalDateTime expireTime) {
        // 审计 D3：原实现两次调用 LocalDateTime.now()，理论上跨秒/跨零点会算出不一致的两个值；
        // 现在只取一次「此刻」，且与写入端同一基准。
        LocalDateTime now = timeSource.now();
        if (expireTime.isBefore(now)) {
            return 0;
        }
        long seconds = Duration.between(now, expireTime).getSeconds();
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.toIntExact(seconds);
    }
}
