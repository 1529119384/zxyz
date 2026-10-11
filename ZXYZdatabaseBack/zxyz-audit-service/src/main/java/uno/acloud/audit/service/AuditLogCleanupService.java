package uno.acloud.audit.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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
 * <p>
 * <b>批次协议（54 号审计 🟡）</b>：删除按 {@code DELETE ... LIMIT 1000} 分批循环，
 * 每批一个独立事务（{@link #cleanupExpiredLogs} 不在事务内、由 mapper 单语句自治提交），
 * {@code affected == 0} 时退出。此前是一次性 DELETE 全量过期行 —— 积压恢复后单条
 * DELETE 数十万行，binlog/undo 膨胀、长事务锁表。分批后单批影响面恒定，
 * 中断不产生半途大事务，剩余批次等次日重跑收敛。
 */
@Slf4j
@Service
@RefreshScope
public class AuditLogCleanupService {

    /** 单批删除行数上限（DELETE ... LIMIT）：单批影响面恒定，避免大事务 */
    static final int BATCH_LIMIT = 1000;
    /** 批次循环安全上限：防兜底保护误判导致死循环（10 万行足够 1 亿行级的清理任务） */
    static final int MAX_BATCHES = 100_000;

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
        log.info("开始清理审计日志：删除 {} 之前的记录（每批 {} 条）", cutoff, BATCH_LIMIT);
        try {
            long total = 0;
            for (int batch = 1; batch <= MAX_BATCHES; batch++) {
                int deleted = operateLogMapper.deleteOlderThanBatch(cutoff, BATCH_LIMIT);
                total += deleted;
                if (deleted == 0) {
                    // 该时间点之前已无过期数据：正常退出（含首批即 0 的空跑）
                    break;
                }
                if (deleted < BATCH_LIMIT) {
                    // 本批删到的行数少于 LIMIT：剩余行已在一批内删完，不再发下一条 DELETE
                    break;
                }
            }
            log.info("审计日志清理完成：共删除 {} 条记录（保留 {} 天）", total, retentionDays);
        } catch (Exception e) {
            // @Scheduled 任务绝不能向外抛异常：抛出会让后续调度被取消，日志表无限增长。
            // 分批协议下单批失败时此前各批已各自提交，损失面被批次边界截断。
            log.error("审计日志清理失败（已删除的批次保持提交）", e);
        }
    }
}
