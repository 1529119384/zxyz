package uno.acloud.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PasswordPolicy} 单元测试（审计 2.1.5 ② 配套）。
 *
 * <p>这是「注册 / 改密 / 团队建号」三条链路共用的唯一口令口径，所以边界要逐个钉死：
 * 长度是 8 而不是 7、复杂度要求「字母 **且** 数字」而不是「或」，以及
 * 「只校验长度」的那条口子不能悄悄把复杂度也带上（那会是超出审计范围的行为变更）。</p>
 */
class PasswordPolicyTest {

    @Test
    void isValid_shouldAcceptExactlyEightCharsWithLetterAndDigit() {
        assertTrue(PasswordPolicy.isValid("abcdefg1"), "8 位且含字母数字是最小合法口令");
        assertTrue(PasswordPolicy.isValid("passw0rd"));
        assertTrue(PasswordPolicy.isValid("12345678a"));
        assertTrue(PasswordPolicy.isValid(("a1" + "x".repeat(126))), "恰好 128 位应合法");
    }

    @Test
    void isValid_shouldRejectSevenCharsEvenIfComplexEnough() {
        // 7 位：复杂度够了但长度不够 —— 这正是「最小长度提到 8」的意义所在
        assertFalse(PasswordPolicy.isValid("abcdef1"));
    }

    @Test
    void isValid_shouldRequireBothLetterAndDigit() {
        assertFalse(PasswordPolicy.isValid("abcdefgh"), "只有字母不合法");
        assertFalse(PasswordPolicy.isValid("12345678"), "只有数字不合法");
        assertFalse(PasswordPolicy.isValid("!!!!!!!!"), "只有符号不合法");
        // 汉字不是 [A-Za-z]，因此「纯汉字 + 数字」里必须另有拉丁字母或数字…
        assertTrue(PasswordPolicy.isValid("中文中文中文a1"), "含字母与数字即可，非 ASCII 不构成障碍");
        assertFalse(PasswordPolicy.isValid("中文中文中文中文"), "纯非 ASCII 无字母数字，不合法");
    }

    @Test
    void isValid_shouldRejectNullBlankAndOverlong() {
        assertFalse(PasswordPolicy.isValid(null));
        assertFalse(PasswordPolicy.isValid(""));
        assertFalse(PasswordPolicy.isValid("        "), "纯空白不合法（即使长度够）");
        assertFalse(PasswordPolicy.isValid("a1" + "x".repeat(128)), "超上限不合法");
    }

    @Test
    void isValid_shouldRejectLineBreaksButAllowInnerWhitespace() {
        // 复杂度正则刻意不启用 DOTALL。注意 Java 正则在默认模式下 `.` 只排除
        // **行终止符**（`\n` / `\r` / `\u0085` / `\u2028` / `\u2029`），
        // **不包括空格与制表符** —— 这一点最初的断言写反了，被本用例抓出来。
        assertFalse(PasswordPolicy.isValid("abc123\nx"), "含 \\n 不合法");
        assertFalse(PasswordPolicy.isValid("abc123\rx"), "含 \\r 不合法");
        assertFalse(PasswordPolicy.isValid("abc123\u2028x"), "含 U+2028 不合法");
        assertFalse(PasswordPolicy.isValid("abcdef1\n"), "末尾换行也不合法（整串必须匹配）");
        // 行内空格/制表符是允许的（口令短语本就该能带空格），这里把边界写清楚，
        // 避免后来者误以为「含空白即非法」而把规则改严、连带拒掉合法口令。
        assertTrue(PasswordPolicy.isValid("abc 123 x"), "含空格合法");
        assertTrue(PasswordPolicy.isValid("abc123\tx"), "含制表符合法");
    }

    @Test
    void hasValidLength_shouldCheckLengthOnly() {
        // 给历史调用点（团队建号）用的口子：只共享最小长度，不被迫接受复杂度要求
        assertTrue(PasswordPolicy.hasValidLength("abcdefgh"), "纯字母 8 位：长度合法");
        assertTrue(PasswordPolicy.hasValidLength("12345678"), "纯数字 8 位：长度合法");
        assertFalse(PasswordPolicy.hasValidLength("abcdef1"), "7 位不合法");
        assertTrue(PasswordPolicy.hasValidLength(("x".repeat(128))), "恰好上限合法");
        assertFalse(PasswordPolicy.hasValidLength(("x".repeat(129))), "超上限不合法");
        assertFalse(PasswordPolicy.hasValidLength(null));
    }

    @Test
    void messages_shouldStateTheRealLimitsInsteadOfHardcodedNumbers() {
        // 文案必须由常量拼出：硬编码「6 位」曾在 EnterpriseTeamService 里与配置值脱节，
        // 用户按提示改到 6 位仍被拒却看不出原因
        assertTrue(PasswordPolicy.SIZE_MESSAGE.contains(String.valueOf(PasswordPolicy.MIN_LENGTH)));
        assertTrue(PasswordPolicy.SIZE_MESSAGE.contains(String.valueOf(PasswordPolicy.MAX_LENGTH)));
        assertEquals(8, PasswordPolicy.MIN_LENGTH, "最小长度是审计要求的口径，改动需同步前端注册页校验");
        assertEquals(128, PasswordPolicy.MAX_LENGTH);
    }
}
