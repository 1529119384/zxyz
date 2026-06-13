package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailSecretCipherTest {

    @Test
    void encryptShouldHidePlainTextAndDecryptBack() {
        EmailProperties properties = new EmailProperties();
        properties.setConfigSecret("unit-test-secret");
        EmailSecretCipher cipher = new EmailSecretCipher(properties);

        String encrypted = cipher.encrypt("smtp-auth-code");

        assertNotEquals("smtp-auth-code", encrypted);
        assertEquals("smtp-auth-code", cipher.decrypt(encrypted));
    }

    @Test
    void encryptShouldRequireConfigSecret() {
        EmailSecretCipher cipher = new EmailSecretCipher(new EmailProperties());

        assertThrows(BusinessException.class, () -> cipher.encrypt("smtp-auth-code"));
    }
}
