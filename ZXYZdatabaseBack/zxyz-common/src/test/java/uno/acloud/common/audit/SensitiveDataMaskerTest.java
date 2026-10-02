package uno.acloud.common.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SensitiveDataMasker} 行为测试。
 *
 * <p>重点证明「B-13 的泄露路径确实存在且被堵住」：审计参数走
 * {@code Arrays.toString}，产出的是 Lombok {@code @ToString} 的
 * {@code (password=secret)} 形态而非 JSON —— 旧实现只有 JSON 正则，
 * 对这条形态完全无效，明文会一路入库。</p>
 */
class SensitiveDataMaskerTest {

    // ==================== JSON 形态（原有能力，锁定不回归） ====================

    @Test
    void mask_jsonPasswordValue() {
        String masked = SensitiveDataMasker.mask("{\"username\":\"bob\",\"password\":\"s3cr3t\"}");

        assertFalse(masked.contains("s3cr3t"), "JSON 形态的密码必须被打码，实际：" + masked);
        assertTrue(masked.contains("\"password\":\"***\""));
        assertTrue(masked.contains("\"username\":\"bob\""), "非敏感字段不得被误伤");
    }

    @Test
    void mask_jsonSensitiveKeysAreCaseInsensitive() {
        assertFalse(SensitiveDataMasker.mask("{\"Token\":\"abc\"}").contains("abc"));
        assertFalse(SensitiveDataMasker.mask("{\"API_KEY\":\"abc\"}").contains("abc"));
        assertFalse(SensitiveDataMasker.mask("{\"Authorization\":\"abc\"}").contains("abc"));
    }

    // ==================== toString 形态（B-13 泄露路径） ====================

    @Test
    void mask_javaToStringPassword() {
        // Arrays.toString(joinPoint.getArgs()) 对 Lombok DTO 的真实产出形态
        String masked = SensitiveDataMasker.mask(
                "[uno.acloud.user.dto.LinkedAccountTrustRequest(password=s3cr3t)]");

        assertFalse(masked.contains("s3cr3t"),
                "toString 形态的密码必须被打码（旧的 JSON 正则会漏掉它），实际：" + masked);
        assertTrue(masked.contains("password=***"));
    }

    @Test
    void mask_multipleToStringFieldsAndTrailingFields() {
        String masked = SensitiveDataMasker.mask(
                "[InternalCreateTeamUserRequest(username=alice, password=p@ss, name=Alice, token=tok123)]");

        assertFalse(masked.contains("p@ss"), "实际：" + masked);
        assertFalse(masked.contains("tok123"), "实际：" + masked);
        assertTrue(masked.contains("username=alice"), "非敏感字段不得被误伤，实际：" + masked);
        assertTrue(masked.contains("name=Alice"), "密码后面的字段不得被连带吞掉，实际：" + masked);
    }

    @Test
    void mask_masksPrefixedTokenAndPasswordFieldNames() {
        // 带前缀的命名是最容易被「精确键名」漏掉的一类：Lombok @ToString 输出的就是原字段名，
        // 而 DTO 里 oldPassword / resetToken / accessToken 都是货真价实的凭据。
        String masked = SensitiveDataMasker.mask(
                "[resetToken=r3set, csrfToken=csrf, accessToken=acc, oldPassword=old, newPassword=new]");

        // 断言「值已消失」（不可用 contains("old") 之类：字段名本身含这些子串）
        assertFalse(masked.contains("=r3set"), "实际：" + masked);
        assertFalse(masked.contains("=csrf"), "实际：" + masked);
        assertFalse(masked.contains("=acc"), "实际：" + masked);
        assertFalse(masked.contains("=old"), "实际：" + masked);
        assertFalse(masked.contains("=new"), "实际：" + masked);
        assertTrue(masked.contains("resetToken=***"), "实际：" + masked);
    }

    @Test
    void mask_doesNotTouchNonSensitiveLookalikes() {
        // 不含关键词的普通字段名不得被误伤（hash 摘要不是凭据本身）
        String masked = SensitiveDataMasker.mask("[passwordHash=ghi, fileName=report.pdf]");

        assertTrue(masked.contains("passwordHash=ghi"),
                "不含关键词的普通字段不得被误伤，实际：" + masked);
        assertTrue(masked.contains("fileName=report.pdf"), "实际：" + masked);
    }

    @Test
    void isSensitiveFieldName_matchesSubstringsCaseInsensitively() {
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("password"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("oldPassword"));
        assertTrue(SensitiveDataMasker.isSensitiveFieldName("ACCESS_TOKEN"));
        assertFalse(SensitiveDataMasker.isSensitiveFieldName("username"));
        assertFalse(SensitiveDataMasker.isSensitiveFieldName(null));
        assertFalse(SensitiveDataMasker.isSensitiveFieldName(""));
    }

    @Test
    void mask_handlesNestedAndNullValues() {
        assertFalse(SensitiveDataMasker.mask("{password=null, token=none}").contains("none"));
        assertEquals("[password=***]", SensitiveDataMasker.mask("[password=x]"));
    }

    @Test
    void mask_masksValuesContainingSpacesOrQuotes() {
        // 值里含空格/引号时必须吃到真正的字段边界，否则只打掉前半截 —— 假安全
        String masked = SensitiveDataMasker.mask("[QuotedSecretDto(token=\"tok with spaces\")]");

        assertFalse(masked.contains("tok with spaces"), "实际：" + masked);
        assertFalse(masked.contains("spaces"), "不得只打掉值的前半截，实际：" + masked);
    }

    // ==================== 边界 ====================

    @Test
    void mask_nullAndEmptyAreReturnedAsIs() {
        assertNull(SensitiveDataMasker.mask(null));
        assertEquals("", SensitiveDataMasker.mask(""));
    }

    @Test
    void mask_leavesPlainTextUntouched() {
        String plain = "[projectId=1, fileName=report.pdf]";
        assertEquals(plain, SensitiveDataMasker.mask(plain));
    }
}
