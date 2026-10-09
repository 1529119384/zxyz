package uno.acloud.common.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.origin.OriginTrackedValue;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JasyptConfigDecryptingHook} 的行为钉死。
 *
 * <p>三条核心设计各有一条用例：无 ENC 值零动作（本地/测试上下文零影响）、
 * 惰性解密（无密钥时只在真正读 ENC 值才 fail-closed）、包装保留 OriginTrackedValue 语义
 * （yml 值的真实载体，报错定位用）。</p>
 */
class JasyptConfigDecryptingHookTest {

    private static final String PASSWORD = "hook-test-key";

    private static final JasyptProperties PROPERTIES = new JasyptProperties();

    private final JasyptConfigDecryptingHook hook = new JasyptConfigDecryptingHook();

    /** 真实密文：用与生产一致的工厂生成，避免「测试密文」与真格式漂移。 */
    private static String enc(String plaintext) {
        return "ENC(" + JasyptEncryptorFactory.create(PROPERTIES, PASSWORD).encrypt(plaintext) + ")";
    }

    private static StandardEnvironment environmentWith(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", values));
        return environment;
    }

    @Test
    void encValueIsDecryptedOnRead() {
        Map<String, Object> values = new HashMap<>();
        values.put("secret.token", enc("plain-secret"));
        values.put(JasyptPasswordPolicy.PASSWORD_PROPERTY, PASSWORD);
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertEquals("plain-secret", environment.getProperty("secret.token"));
    }

    @Test
    void originTrackedEncValueIsDecryptedAndStillReadable() {
        // yml 值经 YamlPropertySourceLoader 加载后的真实形态是 OriginTrackedValue
        Map<String, Object> values = new HashMap<>();
        values.put("secret.otv", OriginTrackedValue.of(enc("otv-payload")));
        values.put(JasyptPasswordPolicy.PASSWORD_PROPERTY, PASSWORD);
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertEquals("otv-payload", environment.getProperty("secret.otv"));
    }

    @Test
    void nonEncValuesPassThroughUnchanged() {
        Map<String, Object> values = new HashMap<>();
        values.put("plain.key", "plain-value");
        values.put("mixed.plain", "KEEP_ME");
        values.put("mixed.enc", enc("mixed-secret"));
        values.put(JasyptPasswordPolicy.PASSWORD_PROPERTY, PASSWORD);
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        assertEquals("plain-value", environment.getProperty("plain.key"));
        assertEquals("KEEP_ME", environment.getProperty("mixed.plain"));
        assertEquals("mixed-secret", environment.getProperty("mixed.enc"));
    }

    @Test
    void environmentWithoutEncValuesIsNotTouchedAtAll() {
        Map<String, Object> values = new HashMap<>();
        values.put("plain.key", "plain-value");
        StandardEnvironment environment = environmentWith(values);
        int sourcesBefore = environment.getPropertySources().size();

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        // 零动作：不换源、不加源（不存在我们的包装类），顺序与数量原样
        assertEquals(sourcesBefore, environment.getPropertySources().size());
        assertTrue(environment.getPropertySources().stream()
                        .noneMatch(source -> source instanceof JasyptConfigDecryptingHook.DecryptingPropertySource),
                "无 ENC 值的环境不得出现解密包装源");
        assertEquals("plain-value", environment.getProperty("plain.key"));
    }

    @Test
    void encWithoutPasswordFailsOnlyWhenActuallyRead() {
        // 惰性：包装阶段不读密钥（钩子调用本身不抛），首次真正读取 ENC 值才 fail-closed
        Map<String, Object> values = new HashMap<>();
        values.put("secret.token", enc("guarded-secret"));
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> environment.getProperty("secret.token"));
        assertTrue(ex.getMessage().contains("JASYPT_PASSWORD"),
                "缺密钥的报错必须指到 JASYPT_PASSWORD，实际为：" + ex.getMessage());
    }

    @Test
    void wrongPasswordFailsWithPropertyName() {
        Map<String, Object> values = new HashMap<>();
        values.put("secret.token", enc("payload"));
        values.put(JasyptPasswordPolicy.PASSWORD_PROPERTY, "not-the-encryption-key");
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> environment.getProperty("secret.token"));
        assertTrue(ex.getMessage().contains("secret.token"),
                "解密失败的报错必须带上属性名（定位到具体配置），实际为：" + ex.getMessage());
    }

    @Test
    void encWrappedPasswordPropertyItselfIsRejected() {
        // 防递归：主密钥配置项自身若是 ENC(...) 会无限解密自身，必须显式拒绝
        Map<String, Object> values = new HashMap<>();
        values.put("secret.token", enc("payload"));
        values.put(JasyptPasswordPolicy.PASSWORD_PROPERTY, enc("nested-key"));
        StandardEnvironment environment = environmentWith(values);

        hook.postProcessEnvironment(environment, new SpringApplication(Object.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> environment.getProperty("secret.token"));
        assertTrue(ex.getMessage().contains("自身不能是 ENC"),
                "实际为：" + ex.getMessage());
    }

    @Test
    void hookIsRegisteredInSpringFactories() {
        // 注册契约：Boot 4 经 META-INF/spring.factories 的
        // org.springframework.boot.EnvironmentPostProcessor key 加载（boot core jar 实测同机制）。
        // 拼错类名/key = 钩子静默失效 = ENC() 值以密文流转，故做成常驻断言。
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("META-INF/spring.factories")) {
            assertTrue(stream != null, "classpath 上必须存在 META-INF/spring.factories");
            String content = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(content.contains("org.springframework.boot.EnvironmentPostProcessor="),
                    "spring.factories 必须注册 EnvironmentPostProcessor key");
            assertTrue(content.contains(JasyptConfigDecryptingHook.class.getName()),
                    "spring.factories 必须登记 " + JasyptConfigDecryptingHook.class.getName());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
