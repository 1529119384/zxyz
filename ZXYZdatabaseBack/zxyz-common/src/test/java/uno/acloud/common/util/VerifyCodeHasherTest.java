package uno.acloud.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VerifyCodeHasher} 的契约测试。
 *
 * <p>本类里最关键的是两个「金标准向量」用例：期望值不是用被测代码自己算出来的，
 * 而是用<b>另一套实现</b>（Python {@code hmac}/{@code hashlib}）独立算出的常量。
 * 这样任何对算法、编码或域分隔前缀的意外改动都会立刻失败 —— 而这类改动正是
 * 「user-service 与 email-service 摘要口径分叉 ⇒ 自己发的码自己验不过」的成因。</p>
 */
class VerifyCodeHasherTest {

    private static final String PEPPER = "test-pepper";

    // 由 Python hmac.new(b"zxyz:verify-code:v1:test-pepper", b"<code>", sha256).hexdigest() 独立算出
    private static final String GOLDEN_123456 =
            "85d3b15ac42dfd9d5b96457f703bcf60a918292f1aaa8c030c57fe4efac75667";
    private static final String GOLDEN_EMPTY =
            "a70b0ff33820874743298ce140678cd5fbcad0c33fdc879e78f72f256b14a223";
    private static final String GOLDEN_000000 =
            "7ebf017d34114cca9d3ec118caf651e0206532e2fd7205b0fe96693a72d685b4";

    private final VerifyCodeHasher hasher = new VerifyCodeHasher(PEPPER);

    // ==================== 金标准向量：钉住算法 + 域分隔前缀 ====================

    @Test
    void hashShouldMatchIndependentlyComputedGoldenVector() {
        assertEquals(GOLDEN_123456, hasher.hash("123456"));
        assertEquals(GOLDEN_000000, hasher.hash("000000"));
        assertEquals(GOLDEN_EMPTY, hasher.hash(""));
    }

    // ==================== 摘要形状 ====================

    @Test
    void hashShouldProduceLowercaseHexOfDeclaredLength() {
        String digest = hasher.hash("123456");

        assertEquals(VerifyCodeHasher.DIGEST_HEX_LENGTH, digest.length());
        // 建表用的 VARCHAR(64) 就是这个常量：两者必须一起改，故在此显式钉住
        assertEquals(64, VerifyCodeHasher.DIGEST_HEX_LENGTH);
        assertTrue(digest.matches("[0-9a-f]{64}"), "必须是 64 位小写十六进制：" + digest);
    }

    @Test
    void hashShouldBeDeterministicAcrossInstances() {
        // 确定性是本方案的前提：确定性才能让比对继续留在 SQL 层（一条 UPDATE 判成败）
        assertEquals(new VerifyCodeHasher(PEPPER).hash("123456"), hasher.hash("123456"));
    }

    // ==================== pepper 必须真正参与运算 ====================

    @Test
    void hashShouldDependOnPepper() {
        VerifyCodeHasher other = new VerifyCodeHasher("other-pepper");

        assertNotEquals(other.hash("123456"), hasher.hash("123456"),
                "换 pepper 必须换摘要 —— 否则 pepper 没有参与运算，等于没加 pepper");
        // 另一套实现算出的 <other-pepper, 123456> 期望值，进一步确认 pepper 参与了 key
        assertEquals("4bbaa343d086d0eb864e3604b2f82d76c7ff2e9d42f425022229cea60980ed44",
                other.hash("123456"));
    }

    @Test
    void hashShouldDependOnCode() {
        assertNotEquals(hasher.hash("123456"), hasher.hash("123457"));
    }

    // ==================== null / 空串语义（调用点控制流依赖它） ====================

    @Test
    void hashShouldTreatNullAsEmptyStringInsteadOfThrowing() {
        // 验码路径要求「提交空码也要消耗一次尝试」，因此这里不能让调用方被迫加分支
        assertEquals(hasher.hash(""), hasher.hash(null));
        assertEquals(GOLDEN_EMPTY, hasher.hash(null));
    }

    @Test
    void hashOfEmptyCodeShouldNotCollideWithAnySixDigitCode() {
        assertNotEquals(hasher.hash("000000"), hasher.hash(""));
        assertNotEquals(hasher.hash("000000"), hasher.hash(null));
    }

    // ==================== fail-closed：弱 pepper 必须拒绝 ====================

    @Test
    void constructorShouldRejectMissingPepper() {
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher(null));
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher(""));
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher("   "));
    }

    @Test
    void constructorShouldRejectTemplatePlaceholderPepper() {
        // .env.example 里公开写着这个值：拿它当 pepper 等于用一个全互联网都知道的密钥
        assertThrows(IllegalStateException.class,
                () -> new VerifyCodeHasher(VerifyCodeHasher.TEMPLATE_PLACEHOLDER));
        assertThrows(IllegalStateException.class,
                () -> new VerifyCodeHasher("  " + VerifyCodeHasher.TEMPLATE_PLACEHOLDER + "  "));
    }

    @Test
    void constructorShouldRejectPublicDevAndTestPeppersUnlessExplicitlyAllowed() {
        // 这两个值同样公开写在仓库里（application-dev.yml / application-test.yml），
        // 拿它们当 pepper 与「没有 pepper」在安全上等价，所以默认一律拒绝。
        // 这条守卫不是洁癖：两个服务的 application.yml 都写着 spring.profiles.default: dev，
        // 任何一次漏设 SPRING_PROFILES_ACTIVE=prod 的启动都会落到 dev profile 并带出 dev 的 pepper。
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher("dev-only-insecure-pepper"));
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher("test-verify-code-pepper"));
        // 带首尾空白的同值也必须被挡住（trim 之后比对）
        assertThrows(IllegalStateException.class, () -> new VerifyCodeHasher(" dev-only-insecure-pepper "));
    }

    @Test
    void constructorShouldAcceptPublicPepperOnlyWhenExplicitlyAllowed() {
        VerifyCodeHasher allowed = new VerifyCodeHasher("dev-only-insecure-pepper", true);

        assertTrue(allowed.hash("123456").matches("[0-9a-f]{64}"));
        // 放行只影响「是否接受」,不影响密钥派生:同一个 pepper 两次算出的摘要必须一致
        assertEquals(allowed.hash("123456"),
                new VerifyCodeHasher("dev-only-insecure-pepper", true).hash("123456"));
        assertNotEquals(allowed.hash("123456"), hasher.hash("123456"), "与真实 pepper 的摘要必须不同");
    }

    @Test
    void constructorShouldTrimPepperBeforeDerivingKey() {
        // 环境变量 / .env 里极易带进首尾空白。不 trim 的话，「看起来相同」的 pepper 会产生不同摘要，
        // 而这种偏差在跨服务时表现为「自己发的码自己验不过」—— 极难排查，所以统一 trim。
        assertEquals(hasher.hash("123456"), new VerifyCodeHasher("  " + PEPPER + "\t").hash("123456"));
    }

    @Test
    void constructorShouldAcceptRealLookingPepper() {
        assertEquals(GOLDEN_123456, new VerifyCodeHasher(PEPPER).hash("123456"));
    }
}
