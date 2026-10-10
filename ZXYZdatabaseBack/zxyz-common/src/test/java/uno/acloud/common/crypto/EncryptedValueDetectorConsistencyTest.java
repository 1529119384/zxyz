package uno.acloud.common.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P3-2（ISSUE/51）：配置面 {@link EncryptedValueDetector#isEncrypted(String)} 与
 * 工具面 {@code JasyptEncryptor#isEncrypted} 自称「同一口径」，但此前工具面缺
 * 「括号内非空」长度守卫 —— {@code ENC()} 在配置面（PropertySource 钩子）被当
 * 明文透传、在工具面（admin DB 读取路径）被当密文送进 {@code decrypt("")} 抛异常。
 *
 * <p>修复后工具面直接委托配置面判定（永不分叉）。本测试用参数化用例把两处判定
 * 钉成逐值一致，重点钉 {@code ENC()}（两处 false）与 {@code ENC( )}（两处 true，
 * 空格也非空密文）这两个边界。</p>
 */
class EncryptedValueDetectorConsistencyTest {

    /** 消费形态与生产一致：钩子走静态判定，admin 的 ConfigService 走包装类。 */
    private JasyptEncryptor wrapper() {
        return new JasyptEncryptor(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ENC(abc123==)",
            "ENC()",       // 分歧焦点：空密文，配置面旧判 false、工具面旧判 true
            "ENC( )",      // 空格也是非空密文，两处都须 true
            "plain-value",
            "ENC(abc",     // 只有前缀
            "abc)",        // 只有后缀
            "ENC)x(",
            ""
    })
    void detectorAndWrapperJudgeIdentically(String value) {
        boolean expected = EncryptedValueDetector.isEncrypted(value);
        assertEquals(expected, wrapper().isEncrypted(value),
                () -> "配置面与工具面对同一值的 ENC() 判定出现分歧: [" + value + "]");
    }

    @Test
    void nullValueIsJudgedIdentically() {
        assertEquals(EncryptedValueDetector.isEncrypted(null), wrapper().isEncrypted(null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"ENC()"})
    void emptyCiphertextIsTreatedAsPlaintextEverywhere(String value) {
        // 保守侧口径（括号内非空才当密文）：空密文按明文透传，而不是送 decrypt("") 抛异常
        assertEquals(false, EncryptedValueDetector.isEncrypted(value));
        assertEquals(false, wrapper().isEncrypted(value));
    }
}
