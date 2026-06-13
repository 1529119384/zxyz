package uno.acloud.common.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
public class AuditBufferRetryScheduler {

    private final AuditEventPublisher auditEventPublisher;

    public AuditBufferRetryScheduler(AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    @Scheduled(fixedDelayString = "${audit.buffer.retry-interval-ms:30000}")
    public void retryBufferedAuditEvents() {
        if (auditEventPublisher.getBufferSize() == 0) {
            return;
        }
        try {
            int retried = auditEventPublisher.retryBufferedEvents();
            if (retried > 0) {
                log.info("定时重试审计事件缓冲区: 成功发布{}条", retried);
            }
        } catch (Exception e) {
            log.error("定时重试审计事件缓冲区失败", e);
        }
    }
}
