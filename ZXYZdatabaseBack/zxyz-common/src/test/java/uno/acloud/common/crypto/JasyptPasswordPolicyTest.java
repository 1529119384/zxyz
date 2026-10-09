package uno.acloud.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JasyptPasswordPolicy} 的 fail-closed 口径钉死：
 * 缺失、空白、未解析占位符、公开占位值都等于「没有密钥」，必须拒绝并给出可操作的指引。
 */
class JasyptPasswordPolicyTest {

    @Test
    void nullPasswordIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JasyptPasswordPolicy.requireUsablePassword(null));
        assertTrue(ex.getMessage().contains("JASYPT_PASSWORD"),
                "报错必须指到环境变量名，实际为：" + ex.getMessage());
    }

    @Test
    void blankPasswordIsRejected() {
        assertThrows(IllegalStateException.class, () -> JasyptPasswordPolicy.requireUsablePassword("   "));
    }

    @Test
    void unresolvedPlaceholderLiteralIsRejected() {
        // Binder 非严格占位符解析：环境变量缺失时绑定结果是字面量 "${JASYPT_PASSWORD}"，
        // 不拦它就会把字面量当真密钥（看起来在加密，实则谁都推得出来）
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JasyptPasswordPolicy.requireUsablePassword("${JASYPT_PASSWORD}"));
        assertTrue(ex.getMessage().contains("未解析"),
                "报错必须说明这是未解析的占位符，实际为：" + ex.getMessage());
    }

    @Test
    void templatePlaceholderValueIsRejected() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JasyptPasswordPolicy.requireUsablePassword("CHANGE_ME_SOMETHING"));
        assertTrue(ex.getMessage().contains("公开占位值"),
                "报错必须说明这是仓库公开值，实际为：" + ex.getMessage());
    }

    @Test
    void usablePasswordIsTrimmedAndReturned() {
        assertEquals("real-key", JasyptPasswordPolicy.requireUsablePassword("  real-key  "));
    }
}
