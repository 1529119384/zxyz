package uno.acloud.share.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uno.acloud.share.infrastructure.entity.Share;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1：分享令牌 v3 —— 把「签发时刻」纳入 HMAC 输入，并让令牌自带签发时刻以便校验端复算。
 *
 * <p>本测试直接钉住<b>签名输入的拼装格式</b>（{@link #buildTokenPayload_pinsSignedFieldOrderAndIsoFormat}）：
 * 这是整套机制里最脆的一环 —— 签发端与校验端只要对同一时刻拼出不同文本，
 * 症状就是「所有分享链接都打不开」，而单测是唯一能在提交前拦住它的关口。</p>
 */
class ShareTokenCodecTest {

    private static final String SECRET = "unit-test-share-cookie-secret";

    /** Cookie 值允许的字符集（RFC 6265 {@code cookie-octet}）——令牌字符集一变就可能被 ResponseCookie 拒收。 */
    private static final Pattern COOKIE_OCTET = Pattern.compile("^[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*$");

    private static final LocalDateTime CREATE_TIME = LocalDateTime.of(2026, 9, 14, 7, 0, 0);
    private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 14, 8, 30, 15, 123_000_000);

    private static Share share(String shareKey, String password, Long userId, LocalDateTime createTime) {
        Share share = new Share();
        share.setShareKey(shareKey);
        share.setPassword(password);
        share.setUserId(userId);
        share.setCreateTime(createTime);
        return share;
    }

    private static Share defaultShare() {
        return share("abc123", "$2a$10$hashedpassword", 100L, CREATE_TIME);
    }

    // ---------------------------------------------------------------- 令牌结构

    @Test
    @DisplayName("令牌形如 v3|签发时刻|摘要，且签发时刻可被原样取回")
    void buildToken_carriesVersionAndIssuedAt() {
        String token = ShareTokenCodec.buildToken(defaultShare(), SECRET, ISSUED_AT);

        assertTrue(token.startsWith("v3|"), "实际令牌: " + token);
        assertEquals(3, token.split(Pattern.quote("|"), -1).length, "实际令牌: " + token);
        assertEquals(ISSUED_AT, ShareTokenCodec.parseIssuedAt(token), "签发时刻必须能从令牌取回");
    }

    @Test
    @DisplayName("令牌字符集必须落在 RFC 6265 cookie-octet 内（否则 ResponseCookie 会拒收）")
    void buildToken_containsOnlyCookieSafeCharacters() {
        String token = ShareTokenCodec.buildToken(defaultShare(), SECRET, ISSUED_AT);

        assertTrue(COOKIE_OCTET.matcher(token).matches(), "实际令牌: " + token);
    }

    @Test
    @DisplayName("签名输入 = v3:shareKey|password|userId|createTime(ISO)|issuedAt(ISO)（逐字段钉死）")
    void buildTokenPayload_pinsSignedFieldOrderAndIsoFormat() {
        String payload = ShareTokenCodec.buildTokenPayload(defaultShare(), ISSUED_AT);

        assertEquals("v3:abc123|$2a$10$hashedpassword|100|2026-09-14T07:00:00|2026-09-14T08:30:15.123",
                payload,
                "签名输入的格式是签发端/校验端唯一的口径，改动它等于让全部在途令牌失效");
    }

    @Test
    @DisplayName("null 的口令与创建时间序列化为空串（不是字面量 \"null\"）")
    void buildTokenPayload_serializesNullsAsEmptyText() {
        String payload = ShareTokenCodec.buildTokenPayload(share("k", null, 7L, null), ISSUED_AT);

        assertEquals("v3:k||7||2026-09-14T08:30:15.123", payload);
        assertFalse(payload.contains("null"), "payload 里不应出现字面量 null： " + payload);
    }

    // ---------------------------------------------------------------- 签发时刻真的被签名了

    @Test
    @DisplayName("同一分享的每次签发都得到不同令牌（v2 下是恒定的）")
    void buildToken_differsPerIssuedAt() {
        Share share = defaultShare();
        String first = ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT);
        String second = ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT.plusSeconds(1));

        assertNotEquals(first, second, "签发时刻不同必须得到不同令牌");
        assertTrue(ShareTokenCodec.verify(share, SECRET, first));
        assertTrue(ShareTokenCodec.verify(share, SECRET, second));
    }

    @Test
    @DisplayName("篡改令牌里的签发时刻 → 校验不通过（证明 issuedAt 确实进了签名输入）")
    void verify_rejectsTamperedIssuedAt() {
        Share share = defaultShare();
        String token = ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT);
        String[] sections = token.split(Pattern.quote("|"), -1);
        String tampered = sections[0] + "|" + sections[1].replace("08:30:15", "08:30:16") + "|" + sections[2];

        assertNotEquals(token, tampered, "前置条件：篡改后的文本必须与原令牌不同");
        assertFalse(ShareTokenCodec.verify(share, SECRET, tampered),
                "签发时刻被改动后摘要必然不再匹配");
    }

    @Test
    @DisplayName("同一时刻签发是确定的（含密钥），换密钥即失效")
    void buildToken_isDeterministicGivenSecretAndIssuedAt() {
        Share share = defaultShare();

        assertEquals(ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT),
                ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT));
        assertNotEquals(ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT),
                ShareTokenCodec.buildToken(share, "other-secret", ISSUED_AT));
    }

    // ---------------------------------------------------------------- 校验

    @Test
    @DisplayName("通过：令牌由本分享 + 本密钥签发")
    void verify_acceptsMatchingToken() {
        Share share = defaultShare();

        assertTrue(ShareTokenCodec.verify(share, SECRET, ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT)));
    }

    @Test
    @DisplayName("不通过：换密钥 / 换分享 / 分享口令或创建时间已变")
    void verify_rejectsWhenAnySignedFieldDiffers() {
        Share share = defaultShare();
        String token = ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT);

        assertFalse(ShareTokenCodec.verify(share, "another-secret", token), "换密钥必须失效");
        assertFalse(ShareTokenCodec.verify(share("other-key", "$2a$10$hashedpassword", 100L, CREATE_TIME), SECRET, token),
                "换 shareKey 必须失效");

        Share passwordChanged = share("abc123", "$2a$10$newhash", 100L, CREATE_TIME);
        assertFalse(ShareTokenCodec.verify(passwordChanged, SECRET, token), "口令变更必须失效");

        Share createTimeChanged = share("abc123", "$2a$10$hashedpassword", 100L, CREATE_TIME.plusNanos(1));
        assertFalse(ShareTokenCodec.verify(createTimeChanged, SECRET, token), "createTime 变更必须失效");
    }

    @Test
    @DisplayName("不通过：v2 令牌（升级前已发出的 Cookie）—— 刻意不做兼容")
    void verify_rejectsLegacyV2Token() {
        // v2 令牌是「HMAC(v2:shareKey|password|userId|createTime)」的十六进制，不含分段
        String legacyV2Token = "f".repeat(64);

        assertFalse(ShareTokenCodec.verify(defaultShare(), SECRET, legacyV2Token));
        assertNull(ShareTokenCodec.parseIssuedAt(legacyV2Token));
    }

    @Test
    @DisplayName("不通过：token 为 null/空白/段数不对/摘要为空；share 为 null")
    void verify_rejectsMalformedInput() {
        Share share = defaultShare();

        assertFalse(ShareTokenCodec.verify(share, SECRET, null));
        assertFalse(ShareTokenCodec.verify(share, SECRET, ""));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "   "));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "v3"));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "v3|"));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "v3|2026-09-14T08:30:15.123|"));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "v2|2026-09-14T08:30:15.123|deadbeef"));
        assertFalse(ShareTokenCodec.verify(share, SECRET, "v3|2026-09-14T08:30:15.123|dead|beef"));
        assertFalse(ShareTokenCodec.verify(null, SECRET, ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT)));
    }

    @Test
    @DisplayName("口令与创建时间为 null 的分享也能正常签发与校验（不抛 NPE）")
    void buildAndVerify_tolerateNullPasswordAndCreateTime() {
        Share share = share("key-null-fields", null, 9L, null);
        String token = ShareTokenCodec.buildToken(share, SECRET, ISSUED_AT);

        assertTrue(ShareTokenCodec.verify(share, SECRET, token));
        assertEquals(ISSUED_AT, ShareTokenCodec.parseIssuedAt(token));
    }

    @Test
    @DisplayName("parseIssuedAt 对非 v3 令牌返回 null（不抛异常）")
    void parseIssuedAt_returnsNullInsteadOfThrowing() {
        assertNull(ShareTokenCodec.parseIssuedAt(null));
        assertNull(ShareTokenCodec.parseIssuedAt(""));
        assertNull(ShareTokenCodec.parseIssuedAt("v3|not-a-datetime|deadbeef"));
    }

    @Test
    @DisplayName("签名摘要为 64 位十六进制（Redis 去重键按前 16 字符取哈希的既有约定不受影响）")
    void buildToken_signatureIsSha256Hex() {
        String token = ShareTokenCodec.buildToken(defaultShare(), SECRET, ISSUED_AT);
        String signature = token.substring(token.lastIndexOf('|') + 1);

        assertEquals(64, signature.length(), "实际摘要: " + signature);
        assertTrue(signature.matches("[0-9a-f]{64}"), "实际摘要: " + signature);
    }

    @Test
    @DisplayName("同一时刻为不同分享签发的令牌互不相同（无碰撞的最小证据）")
    void buildToken_distinctAcrossShares() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 8; i++) {
            tokens.add(ShareTokenCodec.buildToken(share("key-" + i, null, (long) i, CREATE_TIME), SECRET, ISSUED_AT));
        }

        assertEquals(8, tokens.size());
    }

    @Test
    @DisplayName("令牌不是「分享行的哈希」：令牌本身不含 shareKey / 口令明文")
    void buildToken_doesNotLeakShareFields() {
        String token = ShareTokenCodec.buildToken(defaultShare(), SECRET, ISSUED_AT);

        assertNotNull(token);
        assertFalse(token.contains("abc123"), "令牌不应回显 shareKey: " + token);
        assertFalse(token.contains("$2a$10$hashedpassword"), "令牌不应回显口令摘要: " + token);
    }
}
