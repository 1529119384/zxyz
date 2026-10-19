package uno.acloud.email.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.email.infrastructure.EmailRecordMapper;

import java.time.LocalDateTime;

/**
 * 66 号二.5：email_record 分批清理任务 —— 表含 MEDIUMTEXT 正文，零清理导致
 * 无限增长（全仓此前 0 条 DELETE）。只删终态行（SENT/FAILED），PENDING/SENDING
 * 永不清理。分批 DELETE 协议照 audit 库 operate_log 模式（单批影响面恒定）。
 */
@Slf4j
@Component
public class EmailRecordCleanupTask {

    static final int BATCH_LIMIT = 1000;
    static final int MAX_BATCHES = 100_000;

    private final EmailRecordMapper emailRecordMapper;
    /** 保留天数，默认 90 天 */
    private final int retentionDays;

    public EmailRecordCleanupTask(EmailRecordMapper emailRecordMapper,
                                  @Value("${app.email.record.retention-days:90}") int retentionDays) {
        this.emailRecordMapper = emailRecordMapper;
        this.retentionDays = retentionDays;
    }

    /** 每日 03:50（错开 verify-code 03:00 与其它 03 点档任务） */
    @Scheduled(cron = "0 50 3 * * ?")
    public void cleanupStaleRecords() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        long total = 0;
        try {
            for (int batch = 1; batch <= MAX_BATCHES; batch++) {
                int deleted = emailRecordMapper.deleteTerminalOlderThanBatch(cutoff, BATCH_LIMIT);
                total += deleted;
                if (deleted == 0 || deleted < BATCH_LIMIT) {
                    break;
                }
            }
            if (total > 0) {
                log.info("email_record 过期终态行清理完成: 共 {} 行（保留 {} 天）", total, retentionDays);
            }
        } catch (Exception e) {
            log.warn("email_record 清理任务异常", e);
        }
    }
}
