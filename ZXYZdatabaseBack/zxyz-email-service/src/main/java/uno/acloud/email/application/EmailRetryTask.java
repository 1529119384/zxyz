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
            // P2-2（ISSUE/54）：先回捞「markSending 后进程崩溃遗留」的 SENDING 僵尸记录，
            // 把它们归还为 PENDING；随后 dispatchDueRecords 会在同一次调度里把它们捞起重发。
            // 回捞失败不阻断本轮正常重试（catch 在外层统一兜底）。
            int recovered = emailDispatchService.recoverStaleSending();
            if (recovered > 0) {
                log.info("本轮已回捞 {} 条 SENDING 卡死记录，交由本轮重试调度重发", recovered);
            }
            int successCount = emailDispatchService.dispatchDueRecords(emailProperties.getBatchSize());
            if (successCount > 0) {
                log.info("邮件发送任务完成，成功数量={}", successCount);
            }
        } catch (Exception e) {
            log.warn("邮件发送重试任务异常", e);
        }
    }
}
