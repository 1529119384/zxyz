package uno.acloud.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 验证码摘要（HMAC-SHA256）—— 全仓唯一口径，供 {@code user-service} 与 {@code email-service} 共用。
 *
 * <h2>为什么放在 zxyz-common 而不是各服务各写一份</h2>
 * <p>验证码是「发码」与「验码」两个动作跨越两个服务：邮件码由 {@code email-service} 发、
 * {@code email-service} 验；手机码由 {@code user-service} 发、{@code user-service} 验。
 * 一旦两边的摘要算法（算法名、编码、域分隔串）出现任何差异，症状是「自己发的码自己验不过」
 * —— 而且是全站验证码不可用。把实现收敛到一处，是唯一能保证口径不分叉的做法。</p>
 *
 * <h2>为什么要加 pepper（而不是直接哈希 6 位码）</h2>
 * <p>验证码空间只有 10<sup>6</sup>。若库里存的是 {@code SHA256(code)}，任何拿到库读权限的人
 * 都能在毫秒级穷举出全部 10<sup>6</sup> 个候选并反查明文 —— 等价于没加密。加入一个
 * <b>只存在于运行环境、绝不落库</b>的 pepper 作为 HMAC 密钥后，没有 pepper 就无法离线穷举。</p>
 *
 * <h2>为什么用 HMAC 而不是「加盐慢哈希」（bcrypt / argon2）</h2>
 * <p>哈希是非确定性的（每次盐不同）⇒ 比对只能上移到应用层：先 {@code SELECT} 取摘要、
 * 再在 Java 里比对、最后再 {@code UPDATE 置 used=1}。而现有防爆破语义建立在
 * <b>「一条 UPDATE 原子地判定成败」</b>上（{@code WHERE ... AND code = #{code} AND used = 0
 * AND attempt_count <= maxAttempts AND expire_time >= NOW(3)}），拆成两步会引入 TOCTOU 窗口。
 * HMAC 是<b>确定性</b>的，因此调用方可以「先在 Java 里算出摘要、再把它当参数传给同一条 SQL」，
 * 比对仍然在 SQL 层一次完成 —— <b>防爆破语义零变化</b>，是本项改动成本最低、风险最小的路径。
 * 代价是摘要对所有行使用同一密钥（无逐行盐），但每行本就只服务「一个用户 + 一个 10 分钟窗口」，
 * 逐行盐在这里换不来实际收益，却要付出上面那条原子性。</p>
 *
 * <h2>pepper 与 Jasypt 主密钥复用同一份秘密的取舍</h2>
 * <p>本项目 pepper 缺省回退到 {@code JASYPT_PASSWORD}（见各服务 {@code application.yml}），
 * 好处是不必新增一份需要独立托管、独立轮换的秘密。代价是两者共用一个根秘密：
 * 能读到 pepper 的人同时也能解开配置里的 {@code ENC(...)}。已评估并接受，理由是本项目
 * {@code ENC(...)} 数量为 0 且两者同属「必须保密的根秘密」这一等级。</p>
 * <p>轮换 pepper（或轮换 Jasypt 主密钥）会让<b>在途验证码全部失效</b> —— 但验证码 TTL 是分钟级，
 * 影响窗口天然极小，用户重发一次即可，因此轮换是安全的（不需要回填存量）。</p>
 *
 * <h2>域分隔</h2>
 * <p>HMAC 的密钥是 {@code DOMAIN_PREFIX + pepper}，而不是 pepper 本身。这样即使同一个根秘密
 * 被别的用途复用（例如将来用于签名别的令牌），本用途的摘要也不会与那个用途的摘要混淆。
 * 前缀里的 {@code v1} 是<b>刻意留出的版本位</b>：若将来要换算法/编码，把版本号提升即可，
 * 且从摘要本身一眼能看出它是哪一版规则产生的（便于排障；不用于区分存量行 —— 存量行按
 * 「不回填、TTL 内自然过期」处理，见审计 12-②(b)）。</p>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * // 发码：把明文算成摘要后再落库，明文只用于发信 / （dev 下）回显
 * userMapper.upsertContactVerificationCode(userId, type, hasher.hash(code));
 *
 * // 验码：把用户输入算成摘要，再交给原 SQL 比对（SQL 一条 UPDATE 的原子判定不变）
 * userMapper.consumeContactVerificationCode(userId, type, hasher.hash(input), maxAttempts);
 * }</pre>
 *
 * @author ZXYZ Team
 */
public final class VerifyCodeHasher {

    /**
     * 摘要算法：与 {@code ShareCookieManager} 选型一致（JDK 内置、无第三方依赖）。
     * 16 字节块、32 字节输出，对 6 位码而言强度远超需要。
     */
    private static final String ALGORITHM = "HmacSHA256";

    /** 域分隔前缀（含版本位），见类注释「域分隔」。 */
    private static final String DOMAIN_PREFIX = "zxyz:verify-code:v1:";

    /** 摘要的十六进制长度。建表时 {@code code VARCHAR(64)} 即由此确定。 */
    public static final int DIGEST_HEX_LENGTH = 64;

    /**
     * 仓库模板（{@code .env.example}）里公开出现的占位值。
     * 把它当 pepper 等于「有一个全互联网都知道的密钥」= 没有 pepper，
     * 因此视为未配置并拒绝启动 —— 这个失败必须是响亮的，不能退化成静默的弱配置。
     */
    static final String TEMPLATE_PLACEHOLDER = "CHANGE_ME_JASYPT_PASSWORD";

    private final byte[] macKey;

    /**
     * 以「拒绝一切公开弱密钥」的方式构造，等价于 {@code allowInsecurePepper = false}。
     *
     * @param pepper 密钥，来自配置（{@code VERIFY_CODE_PEPPER}，缺省回退 Jasypt 主密钥）
     */
    public VerifyCodeHasher(String pepper) {
        this(pepper, false);
    }

    /**
     * @param pepper              密钥，来自配置（{@code VERIFY_CODE_PEPPER}，缺省回退 Jasypt 主密钥）
     * @param allowInsecurePepper 是否允许使用「仓库内公开写着」的弱 pepper。
     *                            <b>只有 dev / test profile 才应显式置 true</b>（对应配置项
     *                            {@code app.verification.allow-insecure-pepper} /
     *                            {@code email.verify-code-allow-insecure-pepper}，默认 false）。
     * @throws IllegalStateException pepper 缺失、或为仓库内公开的弱值且未显式放行（fail-closed）
     */
    public VerifyCodeHasher(String pepper, boolean allowInsecurePepper) {
        if (pepper == null || pepper.isBlank()) {
            throw new IllegalStateException(
                    "验证码摘要 pepper 未配置：请设置 VERIFY_CODE_PEPPER，或确保 JASYPT_PASSWORD 非空。"
                            + "拒绝以「无密钥」方式校验验证码（那等同于库里存明文）。");
        }
        String value = pepper.trim();
        if (!allowInsecurePepper) {
            String reason = insecurePepperReason(value);
            if (reason != null) {
                throw new IllegalStateException(reason);
            }
        }
        this.macKey = (DOMAIN_PREFIX + value).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 判定 pepper 是否为「仓库里公开写着」的弱值。
     *
     * <h3>为什么是「公开值清单 + 显式放行」，而不是只挡一个占位符</h3>
     * <p>最初只挡了 {@code .env.example} 里的 {@code CHANGE_ME_JASYPT_PASSWORD}。但仓库里公开的弱值不止一个：
     * {@code application-dev.yml} 的 {@code dev-only-insecure-pepper} 与
     * {@code src/test/resources/application-test.yml} 的 {@code test-verify-code-pepper} 同样是全互联网可见的
     * —— 拿它们当 pepper，与「没有 pepper」在安全上是等价的。</p>
     * <p>而两个服务的 {@code application.yml} 都写着 {@code spring.profiles.default: dev}，
     * 也就是说<b>任何一次漏设 {@code SPRING_PROFILES_ACTIVE=prod} 的启动都会落到 dev profile</b>。
     * 若这里再放行 dev 的 pepper，结果就是「服务照常运行、库里的摘要却可被任何人离线穷举」
     * —— 正是本类要消灭的那种<b>静默弱配置</b>。所以默认一律拒绝；dev / test 必须<b>显式</b>放行，
     * 而放行是写在 profile 配置文件里的，评审时一眼可见。</p>
     *
     * <p>顺带一提：{@code spring.profiles.default: dev} 这个默认值本身还有一个更严重的影响 ——
     * dev profile 里 {@code return-code-in-response: true}，漏设 profile 会让接口<b>直接把验证码回显给调用方</b>。
     * 那个问题不由本类负责（已单独记录），此处只是说明「默认 profile 是 dev」这件事的真实后果。</p>
     *
     * @return 拒绝的理由；不是已知弱值则返回 {@code null}
     */
    private static String insecurePepperReason(String pepper) {
        return switch (pepper) {
            case TEMPLATE_PLACEHOLDER ->
                    "验证码摘要 pepper 仍是仓库模板里的公开占位值（" + TEMPLATE_PLACEHOLDER + "），它等于没有密钥。"
                            + "请设置真实的 VERIFY_CODE_PEPPER 或真实的 JASYPT_PASSWORD。";
            case "dev-only-insecure-pepper" ->
                    "验证码摘要 pepper 是仓库 application-dev.yml 里公开的 dev 值，它等于没有密钥。"
                            + "生产 / 预发请设置真实的 VERIFY_CODE_PEPPER（或 JASYPT_PASSWORD）；"
                            + "若确实要在 dev profile 下运行，请显式设置 allow-insecure-pepper=true。";
            case "test-verify-code-pepper" ->
                    "验证码摘要 pepper 是仓库 application-test.yml 里公开的测试值，它等于没有密钥。"
                            + "若确实要在 test profile 下运行，请显式设置 allow-insecure-pepper=true。";
            default -> null;
        };
    }

    /**
     * 计算验证码摘要。
     *
     * <p><b>null 与空串一律按空串计算</b>，而不是抛异常。原因在调用点的控制流上：验码路径是
     * 「先计一次尝试、再判定码是否正确」，用户提交空码时<b>同样必须消耗一次尝试</b>
     * （否则「提交空码」就成了不计数、可无限重试的探测手段）。若这里对 null/空串抛异常，
     * 调用方就得为它加一条分支，反而更容易把「计数」这一步漏掉。空串的摘要与任何 6 位码的
     * 摘要都不同，因此永远匹配不上库里的行 —— 语义上等价于「码不对」，正是我们要的。</p>
     *
     * @param code 验证码明文（允许 null / 空串，见上）
     * @return 64 位小写十六进制摘要
     */
    public String hash(String code) {
        String value = code == null ? "" : code;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(macKey, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 是 JDK 必备算法，走到这里说明运行环境异常，属不可恢复错误
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
