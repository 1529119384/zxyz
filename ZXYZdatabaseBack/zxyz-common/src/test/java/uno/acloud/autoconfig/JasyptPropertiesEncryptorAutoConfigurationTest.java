package uno.acloud.autoconfig;

import org.jasypt.encryption.StringEncryptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uno.acloud.common.crypto.LazyInitStringEncryptor;
import uno.acloud.common.util.JasyptEncryptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JasyptPropertiesEncryptorAutoConfiguration} 的 Bean 行为钉死：
 * 提供注入源（JasyptEncryptor 包装类零改动）、惰性 fail-closed、用户 Bean 让位。
 */
class JasyptPropertiesEncryptorAutoConfigurationTest {

    private static final String PASSWORD = "autoconfig-test-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JasyptPropertiesEncryptorAutoConfiguration.class));

    @Test
    void beanIsCreatedEvenWithoutPassword() {
        // 无 JASYPT_PASSWORD 的上下文（服务集成测试等）必须照常起——Bean 惰性，不校验密钥
        runner.run(context -> {
            assertTrue(context.containsBean("jasyptStringEncryptor"));
            StringEncryptor encryptor = context.getBean(StringEncryptor.class);
            assertInstanceOf(LazyInitStringEncryptor.class, encryptor);
        });
    }

    @Test
    void roundTripWithPasswordProperty() {
        runner.withPropertyValues("jasypt.encryptor.password=" + PASSWORD)
                .run(context -> {
                    StringEncryptor encryptor = context.getBean(StringEncryptor.class);
                    assertEquals("round-trip", encryptor.decrypt(encryptor.encrypt("round-trip")));
                });
    }

    @Test
    void jasyptEncryptorWrapperGetsOurBeanAndKeepsEncFormat() {
        // ConfigService 侧的消费形态：注入 JasyptEncryptor 包装类，encrypt 产出 ENC(...) 格式
        runner.withPropertyValues("jasypt.encryptor.password=" + PASSWORD)
                .withBean(JasyptEncryptor.class)
                .run(context -> {
                    JasyptEncryptor wrapper = context.getBean(JasyptEncryptor.class);
                    String encrypted = wrapper.encrypt("wrapper-payload");
                    assertTrue(wrapper.isEncrypted(encrypted), "encrypt 产物必须是 ENC(...) 格式");
                    assertEquals("wrapper-payload", wrapper.decrypt(encrypted));
                    assertEquals("plain-kept", wrapper.decrypt("plain-kept"));
                });
    }

    @Test
    void userDefinedEncryptorTakesPrecedence() {
        // @ConditionalOnMissingBean：服务若自定义 StringEncryptor，我们的 Bean 必须让位
        StringEncryptor userBean = new StringEncryptor() {
            @Override
            public String encrypt(String message) {
                return "user:" + message;
            }

            @Override
            public String decrypt(String encryptedMessage) {
                return encryptedMessage.substring("user:".length());
            }
        };
        runner.withBean(StringEncryptor.class, () -> userBean)
                .run(context -> {
                    StringEncryptor resolved = context.getBean(StringEncryptor.class);
                    assertTrue(resolved == userBean, "必须解析到用户 Bean 而不是自动配置的 Bean");
                });
    }

    @Test
    void usingEncryptorWithoutPasswordFailsClosed() {
        // 惰性不等于无密钥可用：真正调用 encrypt/decrypt 时必须响亮失败
        runner.run(context -> {
            StringEncryptor encryptor = context.getBean(StringEncryptor.class);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> encryptor.encrypt("anything"));
            assertTrue(ex.getMessage().contains("JASYPT_PASSWORD"),
                    "实际为：" + ex.getMessage());
        });
    }
}
