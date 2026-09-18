package uno.acloud.email.infrastructure;

import jakarta.mail.Message;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.application.EmailSendingAvailabilityService;
import uno.acloud.email.application.EmailServerConfigService;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailServerConfig;
import uno.acloud.email.domain.EmailSenderSnapshot;
import uno.acloud.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.Locale;

@Component
public class SimpleJavaMailSender {

    /** 发件人显示名。 */
    private static final String SENDER_DISPLAY_NAME = "指绣云章";

    private final EmailProperties emailProperties;
    private final EmailServerConfigService emailServerConfigService;
    private volatile MailerCacheEntry mailerCacheEntry;

    public SimpleJavaMailSender(EmailProperties emailProperties,
                                EmailServerConfigService emailServerConfigService) {
        this.emailProperties = emailProperties;
        this.emailServerConfigService = emailServerConfigService;
    }

    public EmailSenderSnapshot send(EmailRecord record) {
        if (!emailProperties.isEnabled()) {
            // 关闭发送时不能伪装成功，否则验证码和历史记录都会误导用户。
            throw new BusinessException(ErrorCode.BAD_REQUEST, EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE);
        }
        EmailServerConfig config = emailServerConfigService.requireActiveConfig();
        Email email = buildEmail(record, config);
        getMailer(config).sendMail(email);
        return new EmailSenderSnapshot(config.getId(), config.getConfigName(), config.getUsername());
    }

    /**
     * 组装待发送邮件（不建立任何连接）。
     * <p>抽成包级可见方法，便于单测直接断言「收件人 / 主题 / 正文 / 发件人」，无需真实 SMTP。</p>
     * <p><b>simple-java-mail 9.x 迁移点</b>：{@code EmailPopulatingBuilder#to(String)} 及其整个
     * {@code to(...)} 重载族已被移除，收件人只能经
     * {@link EmailPopulatingBuilder#withRecipients(Recipient...)} 传入 ⇒ 这里显式构造
     * {@link Recipient} 并指定 {@link Message.RecipientType#TO}。</p>
     */
    Email buildEmail(EmailRecord record, EmailServerConfig config) {
        return EmailBuilder.startingBlank()
                .from(SENDER_DISPLAY_NAME, resolveFromAddress(config))
                .withRecipients(new Recipient(null, record.getRecipient(), Message.RecipientType.TO, null))
                .withSubject(record.getSubject())
                .withHTMLText(record.getContentHtml())
                .buildEmail();
    }

    private Mailer getMailer(EmailServerConfig config) {
        MailerCacheEntry current = mailerCacheEntry;
        if (current != null && current.matches(config)) {
            return current.mailer();
        }
        synchronized (this) {
            current = mailerCacheEntry;
            if (current == null || !current.matches(config)) {
                mailerCacheEntry = new MailerCacheEntry(config.getId(), config.getUpdateTime(), createMailer(config));
            }
            return mailerCacheEntry.mailer();
        }
    }

    /**
     * 构建 {@link Mailer}（{@code MailerBuilder} 只做配置，不建连；建连发生在
     * {@code testConnection()} / {@code sendMail()} 时）。抽成包级可见方法以便单测注入替身。
     */
    Mailer createMailer(EmailServerConfig config) {
        return MailerBuilder
                .withSMTPServer(
                        config.getHost(),
                        config.getPort(),
                        config.getUsername(),
                        emailServerConfigService.decryptPassword(config)
                )
                .withTransportStrategy(resolveTransportStrategy(config))
                .buildMailer();
    }

    private String resolveFromAddress(EmailServerConfig config) {
        return config.getFromAddress() == null || config.getFromAddress().isBlank()
                ? config.getUsername()
                : config.getFromAddress();
    }

    /** 传输策略映射；包级可见以便单测覆盖四种取值。 */
    TransportStrategy resolveTransportStrategy(EmailServerConfig config) {
        String strategy = config.getTransportStrategy() == null
                ? "SMTP_TLS"
                : config.getTransportStrategy().toUpperCase(Locale.ROOT);
        return switch (strategy) {
            case "SMTP" -> TransportStrategy.SMTP;
            case "SMTPS", "SMTP_SSL" -> TransportStrategy.SMTPS;
            default -> TransportStrategy.SMTP_TLS;
        };
    }

    private record MailerCacheEntry(Long configId, LocalDateTime updateTime, Mailer mailer) {
        private boolean matches(EmailServerConfig config) {
            return configId != null
                    && configId.equals(config.getId())
                    && java.util.Objects.equals(updateTime, config.getUpdateTime());
        }
    }
}
