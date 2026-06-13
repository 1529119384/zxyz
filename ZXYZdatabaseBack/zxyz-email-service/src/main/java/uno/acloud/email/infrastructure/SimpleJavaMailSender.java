package uno.acloud.email.infrastructure;

import org.simplejavamail.api.email.Email;
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
        Email email = EmailBuilder.startingBlank()
                .from("指绣云章", resolveFromAddress(config))
                .to(record.getRecipient())
                .withSubject(record.getSubject())
                .withHTMLText(record.getContentHtml())
                .buildEmail();
        getMailer(config).sendMail(email);
        return new EmailSenderSnapshot(config.getId(), config.getConfigName(), config.getUsername());
    }

    private Mailer getMailer(EmailServerConfig config) {
        MailerCacheEntry current = mailerCacheEntry;
        if (current != null && current.matches(config)) {
            return current.mailer();
        }
        synchronized (this) {
            current = mailerCacheEntry;
            if (current == null || !current.matches(config)) {
                Mailer mailer = MailerBuilder
                        .withSMTPServer(
                                config.getHost(),
                                config.getPort(),
                                config.getUsername(),
                                emailServerConfigService.decryptPassword(config)
                        )
                        .withTransportStrategy(resolveTransportStrategy(config))
                        .buildMailer();
                mailerCacheEntry = new MailerCacheEntry(config.getId(), config.getUpdateTime(), mailer);
            }
            return mailerCacheEntry.mailer();
        }
    }

    private String resolveFromAddress(EmailServerConfig config) {
        return config.getFromAddress() == null || config.getFromAddress().isBlank()
                ? config.getUsername()
                : config.getFromAddress();
    }

    private TransportStrategy resolveTransportStrategy(EmailServerConfig config) {
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
