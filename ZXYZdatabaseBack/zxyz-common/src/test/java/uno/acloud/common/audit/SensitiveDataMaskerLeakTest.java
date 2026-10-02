package uno.acloud.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1 回炉回归测试：审核员（AUDIT-1）对抗性验证中**实测泄漏**的形态。
 *
 * <p>本文件里的每个 {@code leak_*} 用例都对应审核报告
 * {@code ISSUE/review-2026-10-02/AUDIT-BACKEND-2026-10-03.md} §3.1 的一条
 * {@code [LEAK]}。它们在修复前必须是红的 —— 这是「先证明当前泄漏、再证明修复后不泄漏」
 * 的红→绿证据，而不是事后补的装饰性断言。</p>
 *
 * <p>★ 标记的是**可达**泄露：用真实 {@code ObjectMapper} 产出的输入，
 * 口令含双引号时 Jackson 必然转义 ⇒ 不是理论风险。</p>
 */
class SensitiveDataMaskerLeakTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 断言文本中不含明文（注意：不可用裸子串，字段名本身可能含关键词）。 */
    private static void assertNoPlaintext(String masked, String secret) {
        assertFalse(masked.contains(secret),
                "明文「" + secret + "」仍残留，实际：" + masked);
    }

    // ==================== 基线：这些原本就该通过（防回炉时改坏） ====================

    @Test
    void baseline_jsonPassword() {
        String masked = SensitiveDataMasker.mask("{\"password\":\"s3cr3t\"}");
        assertNoPlaintext(masked, "s3cr3t");
        assertEquals("{\"password\":\"***\"}", masked);
    }

    @Test
    void baseline_toStringPasswordEquals() {
        String masked = SensitiveDataMasker.mask("[User(password=s3cr3t)]");
        assertNoPlaintext(masked, "s3cr3t");
    }

    @Test
    void baseline_uppercaseAndPwdAndNested() {
        assertNoPlaintext(SensitiveDataMasker.mask("[User(PASSWORD=s3cr3t)]"), "s3cr3t");
        assertNoPlaintext(SensitiveDataMasker.mask("[User(pwd=s3cr3t)]"), "s3cr3t");
        assertNoPlaintext(
                SensitiveDataMasker.mask("[Outer(inner=Inner(password=s3cr3t))]"), "s3cr3t");
    }

    @Test
    void baseline_valueWithQuoteSpaceNewline() {
        assertNoPlaintext(SensitiveDataMasker.mask("[Q(token=\"tok with spaces\")]"), "tok with spaces");
        assertNoPlaintext(SensitiveDataMasker.mask("[Q(secret=val with space)]"), "val with space");
        assertNoPlaintext(SensitiveDataMasker.mask("[Q(password=line1\\nline2)]"), "line1");
    }

    // ==================== R1-② 审核员实测的泄漏形态 ====================

    /** [LEAK] 下划线变体 {@code pass_word} */
    @Test
    void leak_underscoreVariantPassWord() {
        String masked = SensitiveDataMasker.mask("[User(pass_word=s3cr3t)]");

        assertNoPlaintext(masked, "s3cr3t");
    }

    /** [LEAK] 短横线变体 {@code pass-word}（出现在自定义 toString 里） */
    @Test
    void leak_hyphenVariantPassWord() {
        String masked = SensitiveDataMasker.mask("[U(pass-word=s3cr3t)]");

        assertNoPlaintext(masked, "s3cr3t");
    }

    // ==================== R1-③ 三种分隔符变体各自钉住 ====================
    // 判据（Lead 2026-10-03 确认）：正则分支里写了 ≠ 已覆盖，**必须有测试钉住**才算数。

    /** 变体一：下划线 {@code pass_word} —— toString 与 JSON 两种形态都覆盖。 */
    @Test
    void variant_underscore_passWord() {
        assertNoPlaintext(SensitiveDataMasker.mask("[U(pass_word=s3cr3t)]"), "s3cr3t");
        assertNoPlaintext(SensitiveDataMasker.mask("{\"pass_word\":\"s3cr3t\"}"), "s3cr3t");
    }

    /** 变体二：短横线 {@code pass-word} —— toString 与 JSON 两种形态都覆盖。 */
    @Test
    void variant_hyphen_passWord() {
        assertNoPlaintext(SensitiveDataMasker.mask("[U(pass-word=s3cr3t)]"), "s3cr3t");
        assertNoPlaintext(SensitiveDataMasker.mask("{\"pass-word\":\"s3cr3t\"}"), "s3cr3t");
    }

    /**
     * 变体三：{@code api-key}（短横线）与 {@code api_key}（下划线）。
     *
     * <p>处置：<b>已覆盖</b>（不是盲区）。本项目 {@code KEY} 常量早已列出这两个分支；
     * 此前只是<b>缺测试钉住</b>（审核员指出的正是这一点），现补齐。
     * 两种形态（JSON 键 / toString 字段）都断言。</p>
     */
    @Test
    void variant_hyphenAndUnderscore_apiKey() {
        assertNoPlaintext(SensitiveDataMasker.mask("{\"api-key\":\"AK-123\"}"), "AK-123");
        assertNoPlaintext(SensitiveDataMasker.mask("{\"api_key\":\"AK-456\"}"), "AK-456");
        assertNoPlaintext(SensitiveDataMasker.mask("[U(api-key=AK-789)]"), "AK-789");
        assertNoPlaintext(SensitiveDataMasker.mask("[U(api_key=AK-abc)]"), "AK-abc");
    }

    /** 结构层（反射路径）也必须命中这三种变体 —— 否则「已覆盖」只对文本层成立。 */
    @Test
    void variant_separatorFormsHitStructuralLayer() {
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("pass_word"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("pass-word"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("api-key"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("api_key"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("API-KEY"));
    }

    /** [LEAK] JSON 单引号形态 */
    @Test
    void leak_jsonSingleQuoted() {
        String masked = SensitiveDataMasker.mask("{'password':'s3cr3t'}");

        assertNoPlaintext(masked, "s3cr3t");
    }

    /** [LEAK] JSON 无引号键 */
    @Test
    void leak_jsonUnquotedKey() {
        String masked = SensitiveDataMasker.mask("{password:\"s3cr3t\"}");

        assertNoPlaintext(masked, "s3cr3t");
    }

    /** [LEAK] toString 冒号分隔 */
    @Test
    void leak_toStringColonSeparated() {
        String masked = SensitiveDataMasker.mask("[Q(password: s3cr3t)]");

        assertNoPlaintext(masked, "s3cr3t");
    }

    /** [LEAK] toString 无分隔符连写 */
    @Test
    void leak_toStringSpaceSeparated() {
        String masked = SensitiveDataMasker.mask("[Q(password s3cr3t)]");

        assertNoPlaintext(masked, "s3cr3t");
    }

    // ==================== ★ 可达：JSON 值含转义引号（真实 Jackson 产出） ====================

    @Test
    void leak_escapedQuoteInJsonValue_realJacksonOutput() throws Exception {
        String secret = "s3\"cr3t";
        String json = MAPPER.writeValueAsString(new Credential("bob", secret));

        // 前置：确认 Jackson 确实产出转义形态（保证本用例针对真实输入）
        assertTrue(json.contains("\\\""), "Jackson 应把口令中的双引号转义，实际：" + json);

        String masked = SensitiveDataMasker.mask(json);

        assertNoPlaintext(masked, "cr3t");
        assertNoPlaintext(masked, secret);
        // 打码后仍必须是合法 JSON（旧实现在此产出 {"password":"***"cr3t"} —— 非法）
        JsonNode node = MAPPER.readTree(masked);
        assertEquals("***", node.path("password").asText(),
                "password 字段必须被完整打码，实际：" + masked);
        assertEquals("bob", node.path("username").asText(), "非敏感字段不得被误伤");
    }

    @Test
    void leak_escapedQuoteInToStringValue() {
        String masked = SensitiveDataMasker.mask("[Q(password=s3\\\"cr3t)]");

        assertNoPlaintext(masked, "cr3t");
    }

    // ==================== 显式登记的已知盲区（不是缺陷，但必须写明） ====================

    @Test
    void knownBlindSpot_valueContainsKeywordButKeyIsInnocent() {
        // 键名不敏感 ⇒ 文本正则无从判断该值敏感。这是**设计边界**：
        // 只有把「哪个字段是敏感的」这一信息从对象结构里带出来才能覆盖，
        // 而文本形态已丢失该信息（发布端的反射层负责这一类，见 AbstractLogAspectMaskingTest）。
        String masked = SensitiveDataMasker.mask("[U(name=s3cr3t, token=abc)]");

        assertTrue(masked.contains("[U(name=s3cr3t, token=***)]"),
                "已知盲区：非敏感键名的值不会被文本正则打码，实际：" + masked);
    }

    @Test
    void knownBlindSpot_base64EncodedPayload() {
        // 编码后不可识别 —— 文本正则在原理上无法覆盖（需在对象层拦截）
        String masked = SensitiveDataMasker.mask("[U(data=eyJwYXNzd29yZCI6IngifQ==)]");

        assertTrue(masked.contains("eyJwYXNzd29yZCI6IngifQ=="),
                "已知盲区：编码后的载荷文本正则不可识别，实际：" + masked);
    }

    /** Jackson 序列化用的载体：口令字段名就叫 password。 */
    public static class Credential {
        private final String username;
        private final String password;

        public Credential(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }
}
