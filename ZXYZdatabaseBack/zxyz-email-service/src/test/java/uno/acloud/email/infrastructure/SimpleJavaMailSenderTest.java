package uno.acloud.email.infrastructure;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.application.EmailSendingAvailabilityService;
import uno.acloud.email.application.EmailServerConfigService;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailServerConfig;
import uno.acloud.email.domain.EmailSenderSnapshot;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SimpleJavaMailSender} 单测。
 *
 * <p><b>为什么必须补这一条</b>：simple-java-mail 8.12.6 → 9.x 移除了整个
 * {@code EmailPopulatingBuilder#to(...)} 重载族，迁移点恰好在「收件人」这一核心路径上。
 * 「编译过」只证明换了个写法，不证明收件人仍然被写成 {@code TO}、主题/正文没有串位
 * ⇒ 必须落到断言上。真实投递路径另见 {@link SimpleJavaMailSenderSmtpIT}（默认跳过）。</p>
 *
 * <p>测试不碰网络：{@link SimpleJavaMailSender#buildEmail} 只组装对象，
 * {@code createMailer} 被替身覆盖，因此不会真的连 SMTP。</p>
 */
@ExtendWith(MockitoExtension.class)
class SimpleJavaMailSenderTest {

    private static final String RECIPIENT = "zzc1529119384@163.com";

    @Mock
    private EmailServerConfigService emailServerConfigService;

    // ===== 夹具 =====

    private static EmailProperties enabledProperties() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        return properties;
    }

    private static EmailServerConfig config() {
        EmailServerConfig config = new EmailServerConfig();
        config.setId(7L);
        config.setConfigName("默认 SMTP");
        config.setHost("smtp.example.com");
        config.setPort(465);
        config.setUsername("sender@example.com");
        config.setFromAddress("no-reply@example.com");
        config.setTransportStrategy("SMTP_SSL");
        config.setActive(true);
        config.setUpdateTime(LocalDateTime.of(2026, 9, 18, 10, 0));
        return config;
    }

    private static EmailServerConfig configWithStrategy(String strategy) {
        EmailServerConfig config = config();
        config.setTransportStrategy(strategy);
        return config;
    }

    private static EmailRecord record() {
        EmailRecord record = new EmailRecord();
        record.setId(42L);
        record.setRecipient(RECIPIENT);
        record.setSubject("主题：验证码");
        record.setContentHtml("<p>您的验证码是 123456</p>");
        return record;
    }

    private SimpleJavaMailSender sender(EmailProperties properties) {
        return new SimpleJavaMailSender(properties, emailServerConfigService);
    }

    // ===== 1. 迁移核心：收件人必须仍是 TO 收件人，主题/正文/发件人不串位 =====

    @Test
    void buildEmailShouldAddressSingleRecipientAsTo() {
        Email email = sender(enabledProperties()).buildEmail(record(), config());

        assertEquals(1, email.getRecipients().size(), "收件人应恰好一人");
        Recipient to = email.getRecipients().get(0);
        assertEquals(RECIPIENT, to.getAddress());
        assertEquals(Message.RecipientType.TO, to.getType(),
                "withRecipients 必须显式给 TO，否则会退化成 CC/BCC 而静默投错人");
        assertEquals("主题：验证码", email.getSubject());
        assertEquals("<p>您的验证码是 123456</p>", email.getHTMLText());
        assertEquals("no-reply@example.com", email.getFromRecipient().getAddress());
        assertEquals("指绣云章", email.getFromRecipient().getName());
    }

    @Test
    void buildEmailShouldFallBackToUsernameWhenFromAddressBlank() {
        EmailServerConfig config = config();
        config.setFromAddress("   ");

        Email email = sender(enabledProperties()).buildEmail(record(), config);

        assertEquals("sender@example.com", email.getFromRecipient().getAddress(),
                "fromAddress 为空时必须回退到 SMTP 账号，否则 163 会以「发件人与认证账号不一致」拒收");
    }

    // ===== 2. 传输策略映射（9.x 中 TransportStrategy 仍是同一枚举，需保证映射未走样） =====

    @Test
    void resolveTransportStrategyShouldMapAllSupportedValues() {
        SimpleJavaMailSender sender = sender(enabledProperties());

        assertEquals(TransportStrategy.SMTP_TLS, sender.resolveTransportStrategy(configWithStrategy(null)));
        assertEquals(TransportStrategy.SMTP_TLS, sender.resolveTransportStrategy(configWithStrategy("unexpected")));
        assertEquals(TransportStrategy.SMTP, sender.resolveTransportStrategy(configWithStrategy("SMTP")));
        assertEquals(TransportStrategy.SMTPS, sender.resolveTransportStrategy(configWithStrategy("SMTPS")));
        assertEquals(TransportStrategy.SMTPS, sender.resolveTransportStrategy(configWithStrategy("SMTP_SSL")));
        // 库里可能存小写，必须大小写不敏感
        assertEquals(TransportStrategy.SMTPS, sender.resolveTransportStrategy(configWithStrategy("smtp_ssl")));
    }

    // ===== 3. 关闭发送时不得伪装成功，且不应触碰配置 =====

    @Test
    void sendShouldRejectWhenDisabledWithoutTouchingConfig() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> sender(properties).send(record()));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE, exception.getMessage());
        verifyNoInteractions(emailServerConfigService);
    }

    // ===== 4. 组装出的邮件确实交给了 Mailer，且 Mailer 按配置变化重建 =====

    @Test
    void sendShouldHandBuiltEmailToMailerAndReuseMailerWhileConfigUnchanged() {
        EmailServerConfig config = config();
        when(emailServerConfigService.requireActiveConfig()).thenReturn(config);
        RecordingSender sender = new RecordingSender(enabledProperties(), emailServerConfigService);

        EmailSenderSnapshot snapshot = sender.send(record());
        sender.send(record());

        assertEquals(2, sender.sent.size(), "两次 send 都应真的投递");
        assertEquals(RECIPIENT, sender.sent.get(0).getRecipients().get(0).getAddress());
        assertEquals(Message.RecipientType.TO, sender.sent.get(0).getRecipients().get(0).getType());
        assertEquals("主题：验证码", sender.sent.get(1).getSubject());
        assertEquals(1, sender.mailerCreations.get(), "配置未变时应复用同一个 Mailer（不得每次新建连接池）");
        assertEquals(7L, snapshot.serverConfigId());
        assertEquals("默认 SMTP", snapshot.serverConfigName());
        assertEquals("sender@example.com", snapshot.senderUsername());
    }

    @Test
    void sendShouldRebuildMailerAfterConfigUpdateTimeChanges() {
        EmailServerConfig config = config();
        when(emailServerConfigService.requireActiveConfig()).thenReturn(config);
        RecordingSender sender = new RecordingSender(enabledProperties(), emailServerConfigService);

        sender.send(record());
        config.setUpdateTime(config.getUpdateTime().plusMinutes(1));
        sender.send(record());

        assertEquals(2, sender.mailerCreations.get(), "配置 updateTime 变化后必须重建 Mailer");
    }

    /** 用替身 Mailer 替掉真实 SMTP：只验证「发给谁、发了什么」与缓存语义。 */
    private static final class RecordingSender extends SimpleJavaMailSender {

        private final List<Email> sent = new ArrayList<>();
        private final AtomicInteger mailerCreations = new AtomicInteger();

        private RecordingSender(EmailProperties properties, EmailServerConfigService configService) {
            super(properties, configService);
        }

        @Override
        Mailer createMailer(EmailServerConfig config) {
            mailerCreations.incrementAndGet();
            Mailer mailer = mock(Mailer.class);
            when(mailer.sendMail(any(Email.class))).thenAnswer(invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
            });
            return mailer;
        }
    }
}
