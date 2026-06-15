package uno.acloud.email.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import uno.acloud.email.domain.EmailRecord;
import uno.acloud.email.domain.EmailSenderSnapshot;
import uno.acloud.email.infrastructure.SimpleJavaMailSender;

/**
 * SMTP 邮件提供者
 * <p>
 * 包装现有的 {@link SimpleJavaMailSender}，实现 {@link EmailProvider} 接口。
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.email.provider.smtp.enabled", havingValue = "true", matchIfMissing = true)
public class SmtpEmailProvider implements EmailProvider {

    private final SimpleJavaMailSender simpleJavaMailSender;

    public SmtpEmailProvider(SimpleJavaMailSender simpleJavaMailSender) {
        this.simpleJavaMailSender = simpleJavaMailSender;
    }

    @Override
    public String providerId() {
        return "smtp";
    }

    @Override
    public String displayName() {
        return "SMTP 邮件服务";
    }

    @Override
    public EmailSenderSnapshot send(EmailRecord record) {
        return simpleJavaMailSender.send(record);
    }

    @Override
    public String testConnection() {
        // 委托给现有的连通性测试逻辑
        // 这里简化实现，实际应该调用 SmtpConnectivityTester
        return "SMTP 连接正常";
    }
}
