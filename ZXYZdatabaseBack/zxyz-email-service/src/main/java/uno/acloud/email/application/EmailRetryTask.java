package uno.acloud.email.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.email.config.EmailProperties;

@Slf4j
@Component
public class EmailRetryTask {

    private final EmailDispatchService emailDispatchService;
    private final EmailProperties emailProperties;

    public EmailRetryTask(EmailDispatchService emailDispatchService, EmailProperties emailProperties) {
        this.emailDispatchService = emailDispatchService;
        this.emailProperties = emailProperties;
    }

    @Scheduled(
            initialDelayString = "${email.retry-initial-delay-ms:10000}",
            fixedDelayString = "${email.retry-fixed-delay-ms:60000}"
    )
    public void retryPendingEmails() {
        try {
            int successCount = emailDispatchService.dispatchDueRecords(emailProperties.getBatchSize());
            if (successCount > 0) {
                log.info("邮件发送任务完成，成功数量={}", successCount);
            }
        } catch (Exception e) {
            log.warn("邮件发送重试任务异常", e);
        }
    }
}
