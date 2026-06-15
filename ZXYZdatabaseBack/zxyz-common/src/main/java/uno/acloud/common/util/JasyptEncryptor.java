package uno.acloud.common.util;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Component;

/**
 * Jasypt 配置加密工具类
 * <p>
 * 封装 jasypt-spring-boot-starter 自动配置的 {@link StringEncryptor}，
 * 提供便捷的加密/解密方法和 ENC() 格式判断。
 * </p>
 *
 * <p>加密后的值格式为 {@code ENC(ciphertext)}，可在 Nacos 配置或 application.yml 中直接使用，
 * jasypt-spring-boot-starter 会在 Spring Boot 启动时自动解密。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 注入
 * private final JasyptEncryptor jasyptEncryptor;
 *
 * // 加密
 * String encrypted = jasyptEncryptor.encrypt("my-secret-password");
 * // 输出: ENC(base64_encrypted_value)
 *
 * // 解密
 * String decrypted = jasyptEncryptor.decrypt("ENC(base64_encrypted_value)");
 * // 输出: my-secret-password
 *
 * // 判断是否已加密
 * boolean encrypted = jasyptEncryptor.isEncrypted("ENC(...)");  // true
 * boolean plain = jasyptEncryptor.isEncrypted("hello");          // false
 * }</pre>
 *
 * @author ZXYZ Team
 */
@Component
public class JasyptEncryptor {

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    private final StringEncryptor encryptor;

    /**
     * 注入 jasypt-spring-boot-starter 自动配置的 {@link StringEncryptor}。
     * <p>
     * 算法、IV 生成器、密码等参数在 application-common.yml 中通过
     * {@code jasypt.encryptor.*} 属性统一配置。
     * </p>
     *
     * @param encryptor 由 jasypt-spring-boot-starter 自动创建的加密器
     */
    public JasyptEncryptor(StringEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /**
     * 加密明文字符串
     *
     * @param plainText 明文字符串
     * @return 加密后的字符串，格式为 {@code ENC(ciphertext)}；null 或空字符串原样返回
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        return ENC_PREFIX + encryptor.encrypt(plainText) + ENC_SUFFIX;
    }

    /**
     * 解密加密字符串
     *
     * @param cipherText 加密字符串，格式为 {@code ENC(ciphertext)}
     * @return 解密后的明文字符串；非 ENC 格式原样返回；null 或空字符串原样返回
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        if (isEncrypted(cipherText)) {
            String encryptedValue = cipherText.substring(ENC_PREFIX.length(),
                    cipherText.length() - ENC_SUFFIX.length());
            return encryptor.decrypt(encryptedValue);
        }
        return cipherText;
    }

    /**
     * 判断字符串是否为 ENC() 加密格式
     *
     * @param value 待检查的字符串
     * @return 如果是 {@code ENC(...)} 格式返回 true，否则返回 false
     */
    public boolean isEncrypted(String value) {
        return value != null
                && value.startsWith(ENC_PREFIX)
                && value.endsWith(ENC_SUFFIX);
    }
}
