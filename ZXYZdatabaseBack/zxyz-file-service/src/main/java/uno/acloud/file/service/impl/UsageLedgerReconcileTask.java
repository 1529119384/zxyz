package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

import java.util.List;
import java.util.Map;

/**
 * 配额台账对账任务（P2-C1/C2 兜底）。
 * <p>
 * 增量路径（上传 confirm 加量、彻底删除减量）可能因：校验服务超时、台账写入失败、跨服务
 * 计时竞态、异常回滚边界而累积漂移。本任务以 {@code file_node} 的权威聚合
 * （{@code SUM(file_size) WHERE deleted IN (0,1) GROUP BY scope_key}）为基准，周期性地
 * 用 upsert 覆盖 {@code usage_ledger.used_bytes}（不覆盖 storage_limit），使台账与真实
 * 使用量收敛，杜绝"上传被配额卡死但存量已删"的假性超限。
 * </p>
 * <p>
 * 与 {@link OrphanObjectReconcileTask} 正交：后者清 OSS 孤儿对象，本任务校正配额口径。
 * </p>
 */
@Slf4j
@Component
public class UsageLedgerReconcileTask {

    private final FileMapper fileMapper;
    private final UsageLedgerMapper usageLedgerMapper;

    public UsageLedgerReconcileTask(FileMapper fileMapper, UsageLedgerMapper usageLedgerMapper) {
        this.fileMapper = fileMapper;
        this.usageLedgerMapper = usageLedgerMapper;
    }

    /**
     * 每小时对账一次配额台账。异常不影响调度，仅记日志。
     */
    @Scheduled(fixedRate = 3600000)
    public void reconcileUsageLedger() {
        try {
            List<Map<String, Object>> scopeUsage = fileMapper.selectScopeUsageAll();
            if (scopeUsage == null || scopeUsage.isEmpty()) {
                log.debug("配额台账对账：无任意存活文件，跳过（存量台账交由后续删除倒查校正）");
                return;
            }
            int upserted = 0;
            for (Map<String, Object> row : scopeUsage) {
                String scopeKey = row.get("scopeKey") == null ? null : row.get("scopeKey").toString();
                Number totalBytes = (Number) row.get("totalBytes");
                if (scopeKey == null || totalBytes == null) {
                    continue;
                }
                // upsert 只覆盖 used_bytes，不覆盖 storage_limit（storage_limit 由配额校验维护）
                usageLedgerMapper.upsertUsedForReconcile(scopeKey, totalBytes.longValue());
                upserted++;
            }
            log.info("配额台账对账完成，覆盖作用域数量={}", upserted);
        } catch (Exception e) {
            log.warn("配额台账对账任务异常", e);
        }
    }
}