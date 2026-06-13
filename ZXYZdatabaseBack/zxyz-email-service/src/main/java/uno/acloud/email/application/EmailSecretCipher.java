package uno.acloud.email.application;

import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.exception.BusinessException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class EmailSecretCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String CIPHER_PREFIX = "v1";

    private final EmailProperties emailProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailSecretCipher(EmailProperties emailProperties) {
        this.emailProperties = emailProperties;
    }

    public String encrypt(String plainText) {
        String normalizedPlainText = requireSecretText(plainText, "SMTP 授权码不能为空");
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(normalizedPlainText.getBytes(StandardCharsets.UTF_8));
            return CIPHER_PREFIX + ":"
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipherText);
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SMTP 授权码加密失败");
        }
    }

    public String decrypt(String cipherText) {
        String normalizedCipherText = requireSecretText(cipherText, "SMTP 授权码未配置");
        String[] parts = normalizedCipherText.split(":", 3);
        if (parts.length != 3 || !CIPHER_PREFIX.equals(parts[0])) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SMTP 授权码格式不正确");
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "SMTP 授权码解密失败");
        }
    }

    public boolean hasSecretKey() {
        return emailProperties.getConfigSecret() != null && !emailProperties.getConfigSecret().isBlank();
    }

    private SecretKeySpec resolveKey() throws GeneralSecurityException {
        if (!hasSecretKey()) {
            // 密钥必须来自部署环境，避免 SMTP 授权码以可逆明文形式落库。
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "请先配置 EMAIL_CONFIG_SECRET");
        }
        byte[] key = MessageDigest.getInstance("SHA-256")
                .digest(emailProperties.getConfigSecret().trim().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, KEY_ALGORITHM);
    }

    private String requireSecretText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }
}
