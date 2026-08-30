package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    public FileNodePurgeTask(FileMapper fileMapper,
                             UsageLedgerMapper usageLedgerMapper,
                             FileObjectReferenceManager fileObjectReferenceManager) {
        this.fileMapper = fileMapper;
        this.usageLedgerMapper = usageLedgerMapper;
        this.fileObjectReferenceManager = fileObjectReferenceManager;
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

    private void purgeTree(Long rootId) {
        try {
            List<Long> allIds = fileMapper.collectDescendantIds(List.of(rootId));
            if (allIds == null || allIds.isEmpty()) {
                return;
            }
            List<String> ossKeys = fileMapper.getOssKeysByIds(allIds);
            releaseQuota(allIds);
            int rows = fileMapper.reallyDeleteByIds(allIds, null);
            // 冗余：真正删除物理行后，墓碑(none) 行已由 reallyDeleteByIds 处理；引用释放
            if (rows < allIds.size()) {
                log.warn("回收站清理部分失败 rootId={}, expected={}, actual={}", rootId, allIds.size(), rows);
            }
            fileObjectReferenceManager.releaseReferences(ossKeys);
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