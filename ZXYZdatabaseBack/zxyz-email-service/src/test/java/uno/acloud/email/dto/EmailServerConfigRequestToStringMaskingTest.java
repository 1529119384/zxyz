package uno.acloud.email.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-13(email侧)：审计/日志路径经 {@code Arrays.toString} 或人工拼串时走 Lombok
 * {@code @ToString}。{@code @JsonProperty(WRITE_ONLY)} 只管 Jackson JSON 序列化、
 * <b>不管 toString()</b>，故 SMTP 授权码字段必须显式 {@code @ToString(exclude)}，
 * 否则明文授权码可经 toString 形态泄露。
 */
class EmailServerConfigRequestToStringMaskingTest {

    @Test
    void toString应排除SMTP授权码明文() {
        EmailServerConfigRequest request = new EmailServerConfigRequest();
        request.setConfigName("主邮件服务器");
        request.setHost("smtp.example.com");
        request.setPort(587);
        request.setUsername("user@example.com");
        request.setPassword("plain-auth-code-456");
        request.setFromAddress("no-reply@example.com");

        String text = request.toString();

        assertFalse(text.contains("plain-auth-code-456"), "toString 不得包含明文授权码：" + text);
        // 其余字段仍可读
        assertTrue(text.contains("smtp.example.com"));
        assertTrue(text.contains("user@example.com"));
    }

    /** 实体 EmailServerConfig.passwordCipher（密文）同根因：toString 也须排除。 */
    @Test
    void 实体EmailServerConfig的ToString应排除授权码密文() {
        uno.acloud.email.domain.EmailServerConfig config = new uno.acloud.email.domain.EmailServerConfig();
        config.setConfigName("主邮件服务器");
        config.setPasswordCipher("v1:cGxhaW52ZXh0Q2lwaGVydGV4dA==:c2lnbmF0dXJl");

        String text = config.toString();

        assertFalse(text.contains("v1:cGxhaW52ZXh0Q2lwaGVydGV4dA=="), "toString 不得包含授权码密文：" + text);
        assertTrue(text.contains("主邮件服务器"));
    }
}
