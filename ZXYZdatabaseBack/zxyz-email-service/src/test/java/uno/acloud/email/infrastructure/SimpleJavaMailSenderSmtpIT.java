package uno.acloud.email.infrastructure;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import uno.acloud.email.application.EmailServerConfigService;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailSenderSnapshot;
import uno.acloud.email.domain.EmailServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实投递冒烟测试：走一次真正的 SMTP 握手与投递，验证「9.x 迁移后邮件确实发得出去」
 * —— 这是纯单测（{@link SimpleJavaMailSenderTest}）覆盖不到的那一半。
 *
 * <p><b>默认不执行</b>，需要显式提供凭据环境变量（避免任何凭据入库，也就不会触发 Gitleaks）：</p>
 * <pre>
 * ZXYZ_SMTP_IT=true
 * ZXYZ_SMTP_HOST=smtp.163.com
 * ZXYZ_SMTP_PORT=465
 * ZXYZ_SMTP_STRATEGY=SMTPS
 * ZXYZ_SMTP_USERNAME=&lt;发件邮箱&gt;
 * ZXYZ_SMTP_PASSWORD=&lt;SMTP 授权码&gt;
 * ZXYZ_SMTP_TO=&lt;收件邮箱，可省略，默认与 USERNAME 相同&gt;
 * </pre>
 * <p>本机跑法（仅在本地 shell 里设置，不要写进仓库/脚本）：</p>
 * <pre>
 * mvn -pl zxyz-email-service -am test -Dtest=SimpleJavaMailSenderSmtpIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>类名以 {@code IT} 结尾 ⇒ 不在 surefire 的默认匹配内，CI 的 {@code mvn -B test} 不会跑到它。</p>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "ZXYZ_SMTP_IT", matches = "(?i)true")
class SimpleJavaMailSenderSmtpIT {

    @Test
    void shouldDeliverRealEmailThroughConfiguredSmtp() {
        String host = require("ZXYZ_SMTP_HOST");
        int port = Integer.parseInt(System.getenv().getOrDefault("ZXYZ_SMTP_PORT", "465"));
        String strategy = System.getenv().getOrDefault("ZXYZ_SMTP_STRATEGY", "SMTPS");
        String username = require("ZXYZ_SMTP_USERNAME");
        String password = require("ZXYZ_SMTP_PASSWORD");
        String to = System.getenv().getOrDefault("ZXYZ_SMTP_TO", username);

        EmailServerConfig config = new EmailServerConfig();
        config.setId(1L);
        config.setConfigName("IT");
        config.setHost(host);
        config.setPort(port);
        config.setUsername(username);
        config.setFromAddress(username); // 163 要求发件地址与认证账号一致
        config.setTransportStrategy(strategy);
        config.setActive(true);

        EmailServerConfigService configService = mock(EmailServerConfigService.class);
        when(configService.requireActiveConfig()).thenReturn(config);
        when(configService.decryptPassword(config)).thenReturn(password);

        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);

        EmailRecord record = new EmailRecord();
        record.setId(1L);
        record.setRecipient(to);
        record.setSubject("【ZXYZ】simple-java-mail 9.x 迁移真投递验证");
        record.setContentHtml("<p>这封邮件由 <b>SimpleJavaMailSenderSmtpIT</b> 真实投递，"
                + "用于验证 8.12.6 → 9.3.4 迁移后收件人仍为 TO。</p>");

        SimpleJavaMailSender sender = new SimpleJavaMailSender(properties, configService);

        // 只投递一次：重复调用会多寄一封真实邮件，且第二次的断言与第一次完全同义。
        EmailSenderSnapshot snapshot = sender.send(record);

        assertNotNull(snapshot, "send 必须返回发送快照而不是 null");
        assertEquals(username, snapshot.senderUsername(), "发件账号必须与配置一致");
        assertEquals(config.getId(), snapshot.serverConfigId(), "快照应回带命中的配置 id");
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + name);
        }
        return value;
    }
}
