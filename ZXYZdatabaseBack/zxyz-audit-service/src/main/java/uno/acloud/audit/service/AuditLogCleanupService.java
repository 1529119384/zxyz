package uno.acloud.audit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uno.acloud.audit.mapper.OperateLogMapper;

import java.time.LocalDateTime;

/**
 * 审计日志定时清理服务。
 * <p>
 * 每天凌晨 3 点执行，删除超过保留天数的审计日志记录，
 * 防止 operate_log 表无限增长。
 * <p>
 * 保留天数通过 {@code app.audit.retention-days} 配置，默认 90 天。
 */
@Slf4j
@Service
public class AuditLogCleanupService {

    private final OperateLogMapper operateLogMapper;
    /** 审计日志保留天数，默认 90 天 */
    private final int retentionDays;

    public AuditLogCleanupService(OperateLogMapper operateLogMapper,
                                  @Value("${app.audit.retention-days:90}") int retentionDays) {
        this.operateLogMapper = operateLogMapper;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("开始清理审计日志：删除 {} 之前的记录", cutoff);
        try {
            int deleted = operateLogMapper.deleteOlderThan(cutoff);
            log.info("审计日志清理完成：删除 {} 条记录（保留 {} 天）", deleted, retentionDays);
        } catch (Exception e) {
            log.error("审计日志清理失败", e);
        }
    }
}
