package uno.acloud.share.controller.support;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.infrastructure.entity.Share;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class ShareCookieManager {
    private static final String COOKIE_PREFIX = "share_access_";
    private static final String TOKEN_VERSION_PREFIX = "v2:";

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

    public String buildAccessToken(Share share, String cookieSecret) {
        String raw = TOKEN_VERSION_PREFIX + share.getShareKey() + "|" + StringUtils.defaultString(share.getPassword()) + "|" + share.getUserId()
                + "|" + share.getCreateTime();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cookieSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
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
