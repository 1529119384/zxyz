package uno.acloud.email.application;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import org.springframework.stereotype.Component;
import uno.acloud.email.domain.EmailServerConfig;
import uno.acloud.email.domain.EmailTestStatus;
import uno.acloud.email.dto.EmailConnectivityTestVO;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Properties;

@Component
public class SmtpConnectivityTester {

    private static final int TIMEOUT_MS = 10000;

    public EmailConnectivityTestVO test(EmailServerConfig config, String password) {
        LocalDateTime testTime = LocalDateTime.now();
        Properties properties = buildMailProperties(config);
        try {
            Session session = Session.getInstance(properties);
            Transport transport = session.getTransport("smtp");
            try {
                transport.connect(config.getHost(), config.getPort(), config.getUsername(), password);
            } finally {
                if (transport.isConnected()) {
                    transport.close();
                }
            }
            return new EmailConnectivityTestVO(config.getId(), EmailTestStatus.SUCCESS, testTime, "SMTP 连接测试成功");
        } catch (MessagingException e) {
            return new EmailConnectivityTestVO(config.getId(), EmailTestStatus.FAILED, testTime, normalizeMessage(e));
        }
    }

    private Properties buildMailProperties(EmailServerConfig config) {
        Properties properties = new Properties();
        properties.put("mail.smtp.auth", "true");
        properties.put("mail.smtp.host", config.getHost());
        properties.put("mail.smtp.port", String.valueOf(config.getPort()));
        properties.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
        properties.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
        properties.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
        String strategy = config.getTransportStrategy() == null
                ? "SMTP_TLS"
                : config.getTransportStrategy().toUpperCase(Locale.ROOT);
        if ("SMTPS".equals(strategy) || "SMTP_SSL".equals(strategy)) {
            properties.put("mail.smtp.ssl.enable", "true");
        } else if ("SMTP_TLS".equals(strategy)) {
            properties.put("mail.smtp.starttls.enable", "true");
            properties.put("mail.smtp.starttls.required", "true");
        }
        return properties;
    }

    private String normalizeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return message.length() > 1024 ? message.substring(0, 1024) : message;
    }
}
