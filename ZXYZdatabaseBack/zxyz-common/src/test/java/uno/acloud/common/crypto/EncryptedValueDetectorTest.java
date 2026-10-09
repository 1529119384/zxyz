package uno.acloud.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EncryptedValueDetector}：配置面（PropertySource 钩子）与工具面（JasyptEncryptor）
 * 共用的 ENC() 格式判定口径，两边判法分叉会造成「工具认为加密、钩子认为明文」的缝。
 */
class EncryptedValueDetectorTest {

    @Test
    void encWrappedValueIsDetected() {
        assertTrue(EncryptedValueDetector.isEncrypted("ENC(abc123==)"));
    }

    @Test
    void plainValueIsNotDetected() {
        assertFalse(EncryptedValueDetector.isEncrypted("plain-value"));
    }

    @Test
    void emptyInnerValueIsNotDetected() {
        // "ENC()" 连密文都没有，按明文透传比当密文处理更安全
        assertFalse(EncryptedValueDetector.isEncrypted("ENC()"));
    }

    @Test
    void unterminatedValueIsNotDetected() {
        assertFalse(EncryptedValueDetector.isEncrypted("ENC(abc"));
        assertFalse(EncryptedValueDetector.isEncrypted("abc)"));
    }

    @Test
    void nullValueIsNotDetected() {
        assertFalse(EncryptedValueDetector.isEncrypted(null));
    }

    @Test
    void unwrapStripsShellAndKeepsInner() {
        assertEquals("abc123==", EncryptedValueDetector.unwrap("ENC(abc123==)"));
    }
}
