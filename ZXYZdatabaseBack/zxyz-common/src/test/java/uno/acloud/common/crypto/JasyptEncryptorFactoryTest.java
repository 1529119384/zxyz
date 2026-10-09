package uno.acloud.common.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JasyptEncryptorFactory}：算法/IV/密钥三件套的构建口径，
 * 以及 application-common.yml 注释里承诺过的两条实测不变量（往返 OK、IV 随机化生效）。
 */
class JasyptEncryptorFactoryTest {

    private static final String PASSWORD = "unit-test-jasypt-key";

    private JasyptProperties defaultProperties() {
        return new JasyptProperties();
    }

    @Test
    void roundTripEncryptThenDecryptReturnsPlaintext() {
        var encryptor = JasyptEncryptorFactory.create(defaultProperties(), PASSWORD);
        String cipher = encryptor.encrypt("my-secret-password");
        assertTrue(cipher != null && !cipher.contains("my-secret-password"), "密文不得包含明文");
        assertEquals("my-secret-password", encryptor.decrypt(cipher));
    }

    @Test
    void samePlaintextTwiceYieldsDifferentCiphertext() {
        // RandomIvGenerator 生效的直接证据：同明文两次密文不同（IV 随机化）
        var encryptor = JasyptEncryptorFactory.create(defaultProperties(), PASSWORD);
        assertNotEquals(encryptor.encrypt("same-plain"), encryptor.encrypt("same-plain"));
    }

    @Test
    void decryptWithWrongPasswordFailsLoudly() {
        var encryptor = JasyptEncryptorFactory.create(defaultProperties(), PASSWORD);
        String cipher = encryptor.encrypt("payload");
        var wrongKeyEncryptor = JasyptEncryptorFactory.create(defaultProperties(), "another-key");
        assertThrows(RuntimeException.class, () -> wrongKeyEncryptor.decrypt(cipher),
                "错误主密钥必须被拒，而不是返回垃圾明文");
    }

    @Test
    void blankIvGeneratorClassnameFallsBackToRandomIv() {
        JasyptProperties properties = defaultProperties();
        properties.setIvGeneratorClassname("  ");
        var encryptor = JasyptEncryptorFactory.create(properties, PASSWORD);
        assertEquals("payload", encryptor.decrypt(encryptor.encrypt("payload")));
    }

    @Test
    void unknownIvGeneratorClassnameFailsWithClearMessage() {
        JasyptProperties properties = defaultProperties();
        properties.setIvGeneratorClassname("org.jasypt.iv.NoSuchGenerator");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JasyptEncryptorFactory.create(properties, PASSWORD));
        assertTrue(ex.getMessage().contains("iv-generator-classname"),
                "报错必须指到配置键，实际为：" + ex.getMessage());
    }
}
