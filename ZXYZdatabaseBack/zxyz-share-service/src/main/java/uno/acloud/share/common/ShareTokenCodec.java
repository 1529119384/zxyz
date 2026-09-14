package uno.acloud.share.common;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import uno.acloud.share.infrastructure.entity.Share;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 分享访问令牌的<b>签发与校验</b>（HMAC-SHA256）—— share 服务唯一口径。
 *
 * <h2>令牌结构（v3）</h2>
 * <pre>
 *   token   = "v3" | &lt;issuedAt 的 ISO-8601 文本&gt; | &lt;HMAC-SHA256 十六进制&gt;
 *   payload = "v3:" shareKey | password | userId | createTime(ISO) | issuedAt(ISO)
 * </pre>
 * <p>签名输入里的分隔符用 {@code |}、令牌分段也用 {@code |}：前者是「签名输入内部的字段」，
 * 后者是「Cookie 值的三个段」，两者是彼此独立的字符串、不参与对方的解析；共同点是
 * {@code |} 既不出现在 ISO-8601 日期时间里、也不出现在十六进制摘要里，因此不含歧义。</p>
 *
 * <h2>v2 → v3：把「签发时刻」纳入签名</h2>
 * <p>v2 的签名输入是 {@code "v2:" + shareKey | password | userId | createTime}，<b>不含签发时刻</b>
 * —— 于是同一个分享在当前状态下的令牌是**恒定**的（签发时间完全不受保护）。
 * v3 把 {@code issuedAt} 纳入输入，并把它<b>随令牌一起下发</b>：服务端没有第二次机会知道
 * 「这个令牌是什么时候签的」（{@code share} 表没有该列、令牌本身也是无状态的），
 * 只有让令牌自带签发时刻，校验端才可能复算出同一个摘要。</p>
 *
 * <h2>时间序列化：必须用 ISO_LOCAL_DATE_TIME，绝不能拼 toString()</h2>
 * <p>同一时刻在不同精度下的字符串表示并不唯一（是否带小数秒、小数秒是否补零）。
 * 一旦「签发」与「校验」两侧对同一时刻拼出不同文本，摘要就不同 —— 症状是
 * <b>所有分享链接都打不开</b>，且只能靠线上反馈发现、极难定位。
 * 本类因此<b>只</b>用 {@link DateTimeFormatter#ISO_LOCAL_DATE_TIME} 序列化，且校验端
 * <b>直接复用令牌里那段原始文本</b>（不解析成 {@link LocalDateTime} 再格式化），
 * 从根本上排除「同刻不同文」的可能。</p>
 *
 * <h2>为什么单独抽一个类（而不是留在 ShareCookieManager 里）</h2>
 * <p>{@code ShareCookieManager} 位于 {@code ..controller..} 包，受架构规则
 * 「controller 层不使用 try/catch、异常统一交给 GlobalExceptionHandler」约束；而
 * {@code Mac.getInstance} / {@code Mac#init} 抛的是<b>受检异常</b>，任何调用点都必须 catch。
 * 把签名原语下沉到本类，使 Controller 支撑类只负责 Cookie 读写，既满足架构约束，
 * 也让「签名口径」有唯一的归属与唯一的测试入口（参见 {@code VerifyCodeHasher} 的同款取舍）。</p>
 *
 * <h2>令牌携带签发时刻带来的两个行为变化</h2>
 * <ul>
 *   <li>同一分享的<b>每次签发都会得到不同令牌</b>（v2 下同一分享永远得到同一个令牌）。
 *       配额去重键 {@code zxyz:share:burn:<shareId>:<tokenHash>} 因此从「按分享去重」
 *       精确到「按令牌去重」；但浏览器在 Cookie 有效期内不会触发重新签发
 *       （{@code ShareVerifyResult#passedWithoutNewToken} 不下发新 Cookie），
 *       实际语义仍是「同一访客当日只扣一次配额」。</li>
 *   <li>v2 令牌（即升级前已发出的 Cookie）一律判为不通过 ⇒ 上线时全部分享 Cookie 失效一次，
 *       用户重新输入提取码即可。这里<b>刻意不做 v2 兼容</b>：保留双版本会留下一条
 *       「签发时间不受保护」的旁路，与本项改动的目的相反。</li>
 * </ul>
 *
 * <h2>本类不做什么</h2>
 * <p>不判断分享是否过期、不判断访问次数 —— 那些由 {@code ShareStatusCalculator} /
 * {@code ShareAccessManager} 在每次访问时按 {@code share} 行实时判定（服务端强制），
 * Cookie 的 {@code Max-Age} 只负责让浏览器提前清理，不承担安全职责。</p>
 */
@Slf4j
public final class ShareTokenCodec {

    /** 令牌版本。{@code v2}（不携带签发时刻）一律判为不通过，理由见类注释。 */
    static final String TOKEN_VERSION = "v3";

    /** 令牌分段分隔符：{@code v3|issuedAt|hmac}。 */
    private static final String TOKEN_SECTION_SEPARATOR = "|";

    /** 签名输入内部的字段分隔符。 */
    private static final String PAYLOAD_FIELD_SEPARATOR = "|";

    /** v3 令牌固定三段：版本、签发时刻、摘要。 */
    private static final int TOKEN_SECTION_COUNT = 3;

    /** 摘要算法：JDK 内置、无第三方依赖（与 {@code VerifyCodeHasher} 选型一致）。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private ShareTokenCodec() {
    }

    /**
     * 签发访问令牌（v3）。
     *
     * @param share        分享行（参与签名的是 {@code shareKey / password / userId / createTime}，
     *                     因此这几项任一变化都会让已发出的令牌自动失效）
     * @param cookieSecret 签名密钥（{@code SHARE_COOKIE_SECRET}）
     * @param issuedAt     签发时刻；<b>必须</b>与写入端其他时间同源（见 {@code ShareTimeSource}）
     * @return {@code v3|<issuedAt ISO>|<hmac hex>}
     */
    public static String buildToken(Share share, String cookieSecret, LocalDateTime issuedAt) {
        String issuedAtText = formatTime(issuedAt);
        String payload = buildTokenPayload(share, issuedAtText);
        return TOKEN_VERSION + TOKEN_SECTION_SEPARATOR + issuedAtText + TOKEN_SECTION_SEPARATOR
                + hmacHex(payload, cookieSecret);
    }

    /**
     * 构造签名输入（payload）。
     *
     * <p>公开出来是为了让「签名到底覆盖了哪些字段」可以被测试直接断言，
     * 而不必靠「换一个字段值再看令牌是否变化」间接推断。</p>
     */
    public static String buildTokenPayload(Share share, LocalDateTime issuedAt) {
        return buildTokenPayload(share, formatTime(issuedAt));
    }

    /**
     * 从令牌里取出签发时刻。
     *
     * @return 令牌不是合法 v3 令牌时返回 {@code null}（不抛异常：本方法只用于排障与测试断言，
     *         判定「令牌是否通过」请用 {@link #verify}）
     */
    public static LocalDateTime parseIssuedAt(String token) {
        String[] sections = splitToken(token);
        if (sections == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(sections[1], DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 校验令牌是否由本服务针对<b>这一条分享</b>签发。
     *
     * <p>比较用 {@link MessageDigest#isEqual} 做常量时间比较（与 v2 实现一致），
     * 避免按字节提前返回泄露摘要前缀信息。</p>
     *
     * @param share        当前的分享行（状态/口令等任一变化 ⇒ 令牌失效）
     * @param cookieSecret 签名密钥
     * @param token        客户端 Cookie 里携带的令牌（允许 null / 空白）
     * @return 通过为 {@code true}
     */
    public static boolean verify(Share share, String cookieSecret, String token) {
        if (share == null || StringUtils.isBlank(token)) {
            return false;
        }
        String[] sections = splitToken(token);
        if (sections == null) {
            // 最常见的情形是「升级前签发的 v2 令牌仍留在浏览器里」；走 debug，避免刷日志
            log.debug("分享访问令牌不是 {} 版本（令牌长度 {}），按不通过处理", TOKEN_VERSION, token.length());
            return false;
        }
        byte[] expected = hmacHex(buildTokenPayload(share, sections[1]), cookieSecret)
                .getBytes(StandardCharsets.UTF_8);
        byte[] actual = sections[2].getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 拼签名输入：{@code v3:shareKey|password|userId|createTime|issuedAt}。
     *
     * <p>版本号 {@code v3:} 放在输入最前面，使不同版本的摘要天然分隔
     * （即便将来同一个密钥被别的用途复用，也不会与别的用途产生相同摘要）。</p>
     *
     * <p>{@code issuedAt} 接收的是<b>已经序列化好的文本</b>：校验路径直接把令牌里那段原文
     * 传进来，从而保证「签发端格式化一次、校验端原样复用」，不会出现同刻不同文。</p>
     */
    private static String buildTokenPayload(Share share, String issuedAtText) {
        return TOKEN_VERSION + ":" + StringUtils.defaultString(share.getShareKey())
                + PAYLOAD_FIELD_SEPARATOR + StringUtils.defaultString(share.getPassword())
                + PAYLOAD_FIELD_SEPARATOR + share.getUserId()
                + PAYLOAD_FIELD_SEPARATOR + formatTime(share.getCreateTime())
                + PAYLOAD_FIELD_SEPARATOR + issuedAtText;
    }

    /**
     * 按固定格式序列化时间。
     *
     * <p>{@code null} 序列化成空串（而不是字符串 {@code "null"}）：v2 用字符串拼接时
     * {@code createTime} 为 null 会得到字面量 {@code "null"}，两种写法对同一行算出的摘要不同。
     * 这里统一成空串，语义更明确。</p>
     */
    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    /**
     * 拆解令牌为三段。
     *
     * @return 合法 v3 令牌的三段；任何不合法情形（段数不对、版本不是 v3、签发时刻或摘要为空）
     *         一律返回 {@code null}，由调用方决定如何处置
     */
    private static String[] splitToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }
        String[] sections = token.split(Pattern.quote(TOKEN_SECTION_SEPARATOR), -1);
        if (sections.length != TOKEN_SECTION_COUNT
                || !TOKEN_VERSION.equals(sections[0])
                || sections[1].isEmpty()
                || sections[2].isEmpty()) {
            return null;
        }
        return sections;
    }

    /**
     * 计算 HMAC-SHA256 的十六进制摘要。
     *
     * <p>受检异常在本类内部收敛为 {@link IllegalStateException}：{@code HmacSHA256} 是 JDK
     * 必备算法，走到 catch 说明运行环境异常，属不可恢复错误（与 {@code VerifyCodeHasher} 一致）。</p>
     */
    private static String hmacHex(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
