package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 清理任务：回收站 TTL 到期彻底删除 + 墓碑过期行物理清理（P2-C1）。
 * <p>
 * 语义与 {@link FileLifecycleService#reallyDelete} 一致，但由定时调度驱动，不校验用户权限：
 * <ul>
 *   <li><b>回收站 TTL（默认 30 天）</b>：以 {@code deleted=1} 的<i>根节点</i>（无仍被回收的父节点）
 *       为单位，收集整棵子树，走 mapper 级物理删除，释放 OSS 引用并扣减配额台账，再清理孤立父文件夹；</li>
 *   <li><b>墓碑过期（默认 7 天）</b>：{@code deleted=2} 行保留以支撑对账（quota 口径排除 deleted=2），
 *       避免恢复/清理期间由 rename 产生的原名冲突；过期后物理删行，释放配额。</li>
 * </ul>
 * 每次处理的批次受 {@code batchSize} 限制，避免长事务。
 * </p>
 */
@Slf4j
@Component
public class FileNodePurgeTask {

    /** 回收站保留天数 */
    private static final int RECYCLE_TTL_DAYS = 30;

    /** 墓碑行保留天数 */
    private static final int TOMBSTONE_TTL_DAYS = 7;

    /** 单批处理根节点/墓碑数量上限（避免一次事务过大） */
    private static final int BATCH_LIMIT = 200;

    private final FileMapper fileMapper;
    private final UsageLedgerMapper usageLedgerMapper;
    private final FileObjectReferenceManager fileObjectReferenceManager;
    private final TransactionHelper transactionHelper;

    public FileNodePurgeTask(FileMapper fileMapper,
                             UsageLedgerMapper usageLedgerMapper,
                             FileObjectReferenceManager fileObjectReferenceManager,
                             TransactionHelper transactionHelper) {
        this.fileMapper = fileMapper;
        this.usageLedgerMapper = usageLedgerMapper;
        this.fileObjectReferenceManager = fileObjectReferenceManager;
        this.transactionHelper = transactionHelper;
    }

    /**
     * 每日凌晨清理回收站到期根节点与过期墓碑。
     */
    @Scheduled(cron = "0 17 2 * * *")
    public void purgeExpiredNodes() {
        purgeRecycleExpiredRoots();
        purgeExpiredTombstones();
    }

    private void purgeRecycleExpiredRoots() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds((long) RECYCLE_TTL_DAYS * 24 * 3600));
        List<Long> rootIds = fileMapper.selectRecycleExpiredRootIds(cutoff, BATCH_LIMIT);
        if (rootIds == null || rootIds.isEmpty()) {
            return;
        }
        log.info("回收站到期清理：待清理根节点 count={}", rootIds.size());
        // 系统级清理走 mapper 层，不经过 FileLifecycleService 的用户权限校验
        for (Long rootId : rootIds) {
            purgeTree(rootId);
        }
    }

    /**
     * 清理一棵回收站子树。
     *
     * <h2>F2（P2）为什么必须包在一个事务里</h2>
     * <p>修复前 {@code reallyDeleteByIds}（把 {@code file_node} 置 {@code deleted = 2}）是<b>自动提交</b>的，
     * 之后的 {@code releaseReferences}（{@code file_object_ref.ref_count} 减 1）才另起事务。
     * 两者之间崩溃/异常，就留下「{@code file_node} 已删、{@code ref_count} 未减」的形态：
     * ref 行永停在 {@code ACTIVE}（{@code refCount >= 1}），物理删除管道永不拾取它，
     * 而 {@code OrphanObjectReconcileTask} 只处理<b>无 ref 行</b>的孤儿 ⇒ 对此泄漏完全不可见，
     * <b>OSS 对象永久泄漏</b>。</p>
     * <p>用户路径 {@code FileLifecycleService#reallyDelete} 早就把这两步放在同一事务里
     * （{@code transactionHelper.execute}），定时任务这条路径漏了。此处对齐同一事务边界。</p>
     * <p>注意 {@code releaseReferences} 自身带 {@code @Transactional}（REQUIRED 传播），
     * 在外层事务内调用会<b>加入</b>该事务而不是另开一个 —— 这正是本修复成立的前提。</p>
     */
    private void purgeTree(Long rootId) {
        try {
            transactionHelper.execute(status -> {
                List<Long> allIds = fileMapper.collectDescendantIds(List.of(rootId));
                if (allIds == null || allIds.isEmpty()) {
                    return null;
                }
                List<String> ossKeys = fileMapper.getOssKeysByIds(allIds);
                releaseQuota(allIds);
                int rows = fileMapper.reallyDeleteByIds(allIds, null);
                // 冗余：真正删除物理行后，墓碑(none) 行已由 reallyDeleteByIds 处理；引用释放
                if (rows < allIds.size()) {
                    log.warn("回收站清理部分失败 rootId={}, expected={}, actual={}", rootId, allIds.size(), rows);
                }
                // 与 reallyDeleteByIds 同事务：中途失败整体回滚，不会留下「节点已删但 ref_count 未减」的
                // 永久泄漏（OrphanObjectReconcileTask 只认无 ref 行的孤儿，对 refCount 泄漏不可见）。
                fileObjectReferenceManager.releaseReferences(ossKeys);
                return null;
            });
        } catch (Exception e) {
            log.warn("回收站清理失败 rootId={}", rootId, e);
        }
    }

    private void purgeExpiredTombstones() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds((long) TOMBSTONE_TTL_DAYS * 24 * 3600));
        List<Long> tombstoneIds = fileMapper.selectTombstoneExpiredIds(cutoff, BATCH_LIMIT);
        if (tombstoneIds == null || tombstoneIds.isEmpty()) {
            return;
        }
        releaseQuota(tombstoneIds);
        int rows = fileMapper.deleteTombstoneRows(tombstoneIds);
        log.info("墓碑过期清理：目标 count={}, 已删除={}", tombstoneIds.size(), rows);
    }

    /** 按作用域扣减配额（与 FileLifecycleService.releaseQuota 同一口径）。 */
    private void releaseQuota(List<Long> allIds) {
        try {
            List<Map<String, Object>> scoped = fileMapper.sumDeletedFileBytesByScopeKey(allIds);
            if (scoped == null) {
                return;
            }
            for (Map<String, Object> row : scoped) {
                String scopeKey = row.get("scopeKey") == null ? null : row.get("scopeKey").toString();
                Number totalBytes = (Number) row.get("totalBytes");
                if (scopeKey == null || totalBytes == null || totalBytes.longValue() <= 0) {
                    continue;
                }
                usageLedgerMapper.decrement(scopeKey, totalBytes.longValue());
            }
        } catch (Exception e) {
            log.warn("清理释放配额失败，交由对账校正", e);
        }
    }
}