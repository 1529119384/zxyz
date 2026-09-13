package uno.acloud.share.controller.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.infrastructure.entity.Share;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 D3：Cookie 的 {@code Max-Age} 必须由注入的时间基准算出，且
 * {@code now()} 只取一次（原实现在同一次计算里调了两次，跨秒/跨零点可能不一致）。
 */
class ShareCookieManagerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 0, 0);
    private static final String SET_COOKIE = "Set-Cookie";

    private final ShareCookieManager cookieManager =
            new ShareCookieManager(new ShareTimeSource(FIXED_CLOCK));

    @Test
    @DisplayName("Max-Age = 到期时间 − 此刻（固定时钟下确定）")
    void maxAgeIsDeltaBetweenExpireTimeAndNow() {
        MockHttpServletResponse response = write(NOW.plusHours(1));

        String header = response.getHeader(SET_COOKIE);
        assertTrue(header.contains("Max-Age=3600"), "实际 Set-Cookie: " + header);
    }

    @Test
    @DisplayName("已过期 → Max-Age=0")
    void alreadyExpiredYieldsZeroMaxAge() {
        MockHttpServletResponse response = write(NOW.minusSeconds(1));

        String header = response.getHeader(SET_COOKIE);
        assertTrue(header.contains("Max-Age=0"), "实际 Set-Cookie: " + header);
    }

    @Test
    @DisplayName("超长有效期 → 夹到 Integer 上限，不溢出")
    void tooLongExpiryIsClampedToIntegerMax() {
        // 100 年 ≈ 3.15e9 秒 > Integer.MAX_VALUE(2147483647)
        MockHttpServletResponse response = write(NOW.plusYears(100));

        String header = response.getHeader(SET_COOKIE);
        assertTrue(header.contains("Max-Age=2147483647"), "实际 Set-Cookie: " + header);
    }

    @Test
    @DisplayName("无到期时间 → 不写 Max-Age")
    void nullExpireTimeOmitsMaxAge() {
        MockHttpServletResponse response = write(null);

        String header = response.getHeader(SET_COOKIE);
        assertTrue(header.startsWith("share_access_sharekey="), "实际 Set-Cookie: " + header);
        assertFalse(header.contains("Max-Age"), "实际 Set-Cookie: " + header);
    }

    @Test
    @DisplayName("shareKey 为空白 → 不下发 Cookie")
    void blankShareKeySkipsCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookieManager.writeAccessToken("  ", "token", NOW.plusHours(1), response);

        assertNull(response.getHeader(SET_COOKIE));
    }

    @Test
    @DisplayName("访问令牌为空白 → 不下发 Cookie")
    void blankAccessTokenSkipsCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookieManager.writeAccessToken("sharekey", "", NOW.plusHours(1), response);

        assertNull(response.getHeader(SET_COOKIE));
    }

    @Test
    @DisplayName("Cookie 名带前缀，且 HttpOnly / SameSite=Lax")
    void cookieCarriesSecurityAttributes() {
        MockHttpServletResponse response = write(NOW.plusHours(1));

        String header = response.getHeader(SET_COOKIE);
        assertTrue(header.startsWith("share_access_sharekey="), "实际 Set-Cookie: " + header);
        assertTrue(header.contains("SameSite=Lax"), "实际 Set-Cookie: " + header);
        assertTrue(header.toLowerCase().contains("httponly"), "实际 Set-Cookie: " + header);
    }

    @Test
    @DisplayName("签名令牌确定（无服务端状态），且随口令变化")
    void accessTokenIsDeterministicAndSecretDependent() {
        Share share = new Share();
        share.setShareKey("key-1");
        share.setUserId(7L);
        share.setCreateTime(NOW);

        String first = cookieManager.buildAccessToken(share, "secret");
        String second = cookieManager.buildAccessToken(share, "secret");
        String other = cookieManager.buildAccessToken(share, "other-secret");

        assertEquals(first, second, "同一输入必须得到同一令牌");
        assertNotEquals(first, other, "换口令必须换令牌");
    }

    private MockHttpServletResponse write(LocalDateTime expireTime) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookieManager.writeAccessToken("sharekey", "token-value", expireTime, response);
        return response;
    }
}
