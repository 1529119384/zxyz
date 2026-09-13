package uno.acloud.share.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分享条目对账任务（审计 2.2.7）。
 * <p>
 * <b>背景</b>：{@code share_item} 记录「哪个分享引用了哪个 file_id」，但它只增不减 —— 文件被彻底删除后，
 * 引用它的 share_item 行会永久残留。后果有两层：① 分享页里挂着一个永远打不开的条目；
 * ② 这些残留行让「文件真的删干净了吗」这个问题无法用 SQL 回答。
 * </p>
 * <p>
 * <b>为什么是对账而不是删除时同步清理</b>：share 侧只是最终一致的只读展示，对账足以收敛；
 * 而「删除时同步清理」要改造删除链路的事务与失败重试（本质是本地消息表），成本高一个量级，
 * 且一旦消息丢失就永久漏清 —— 对账天然能兜住历史遗漏。
 * </p>
 * <p>
 * <b>判定口径（本类最关键的一处，改之前务必读完）</b>：一个 share_item 算「孤儿」当且仅当
 * <ul>
 *   <li>对应的 file 行<b>根本不存在</b>（上游返回集里没有它），或</li>
 *   <li>对应 file 的 {@code deleted = 2}（{@link FileDeleteStatus#DELETED}，彻底删除）。</li>
 * </ul>
 * <b>{@code deleted = 1}（回收站）必须保留</b>：用户随时可能还原，还原后分享应当恢复可用；
 * 若把它当孤儿清掉，用户还原文件后会看到一个残缺的分享 —— 而且是不可逆的。
 * </p>
 * <p>
 * ⚠️ <b>不要改用 {@code ShareFileProjection#isActive()} 驱动清理</b>：它只认 {@code deleted == 0}，
 * 那是「访问时判定」（回收站文件不可访问），语义与「对账时判定」不同。用前者驱动清理正是上面那个不可逆错误。
 * 同理，也不要改成调 {@code /batch-share-projection} —— 那个端点走 {@code getActiveFileNodesByIds}，
 * 只返回 {@code deleted = 0}，会把「回收站」和「彻底删除」都变成「查不到」而无法区分。
 * 本类走的是 {@code /batch-share-projection-with-deleted}。
 * </p>
 * <p>
 * <b>灰度策略</b>：默认 {@code deletion-enabled=false}，即只告警不删除 —— 先用一周把数据量看清楚。
 * 打开删除前请确认灰度观测结果，并复核 {@code file_node.deleted} 的取值口径没有变。
 * </p>
 */
@Slf4j
@Component
public class ShareItemReconcileTask {

    /** 分布式锁键。语义是「同一时刻只让一个实例跑对账」，不是正确性前提（见 {@link #tryAcquireLock}）。 */
    private static final String LOCK_KEY = "share:item-reconcile:lock";

    /** 告警里最多打印多少条孤儿明细。不限制会在一轮对账里刷爆日志。 */
    private static final int SAMPLE_LIMIT = 20;

    private final ShareMapper shareMapper;
    private final ShareFileServiceClient fileServiceClient;
    private final StringRedisTemplate redisTemplate;

    private final boolean enabled;
    private final boolean deletionEnabled;
    private final int batchSize;
    private final long lockTtlSeconds;

    public ShareItemReconcileTask(ShareMapper shareMapper,
                                  ShareFileServiceClient fileServiceClient,
                                  StringRedisTemplate redisTemplate,
                                  @Value("${app.share.item-reconcile.enabled:true}") boolean enabled,
                                  @Value("${app.share.item-reconcile.deletion-enabled:false}") boolean deletionEnabled,
                                  @Value("${app.share.item-reconcile.interval-ms:1800000}") long intervalMs,
                                  @Value("${app.share.item-reconcile.batch-size:500}") int batchSize,
                                  @Value("${app.share.item-reconcile.lock-ttl-seconds:1500}") long lockTtlSeconds) {
        this.shareMapper = shareMapper;
        this.fileServiceClient = fileServiceClient;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.deletionEnabled = deletionEnabled;
        // 非法批大小会让 listItemsByCursor 返回空/报错，直接视为「一轮扫一行」最保险。
        this.batchSize = batchSize < 1 ? 1 : batchSize;

        long intervalSeconds = Math.max(1L, intervalMs / 1000L);
        if (intervalSeconds < 120L) {
            log.warn("分享条目对账间隔 {} ms 过短：每轮都会对 file-service 发起批量查询，建议不低于 2 分钟", intervalMs);
        }
        // 锁 TTL 必须**严格小于**调度间隔。否则一旦某一轮异常退出（没走到释放锁的 finally），
        // 锁会一直存在到 TTL 过期 —— 任务被静默停摆，而且没有任何报错，只能靠「对账不再出日志」发现。
        long maxAllowedTtl = Math.max(1L, intervalSeconds - 60L);
        if (lockTtlSeconds > maxAllowedTtl) {
            log.warn("分享条目对账锁 TTL({}s) 不能大于等于调度间隔({}ms)减 60s，已钳制为 {}s",
                    lockTtlSeconds, intervalMs, maxAllowedTtl);
            this.lockTtlSeconds = maxAllowedTtl;
        } else {
            this.lockTtlSeconds = Math.max(1L, lockTtlSeconds);
        }
    }

    /**
     * 对账入口（定时触发）。
     * <p>用 {@code fixedDelay} 而不是 {@code fixedRate}：对账是「跑完再算下一次」，
     * 否则慢任务会与下一轮叠加。另给 5 分钟初始延迟，避开服务刚启动时依赖尚未就绪的窗口。</p>
     */
    @Scheduled(
            initialDelayString = "${app.share.item-reconcile.initial-delay-ms:300000}",
            fixedDelayString = "${app.share.item-reconcile.interval-ms:1800000}")
    public void reconcileShareItems() {
        if (!enabled) {
            log.debug("分享条目对账已禁用（app.share.item-reconcile.enabled=false），跳过");
            return;
        }
        String lockToken = UUID.randomUUID().toString();
        if (!tryAcquireLock(lockToken)) {
            log.info("分享条目对账：已有实例在执行本轮，跳过");
            return;
        }
        try {
            runReconcile();
        } catch (Exception e) {
            // 不让一次失败影响后续轮次：fixedDelay 仍会继续调度下一轮。
            log.warn("分享条目对账任务异常，本轮中止（下一轮会重新执行）", e);
        } finally {
            releaseLock(lockToken);
        }
    }

    private void runReconcile() {
        long startedAt = System.nanoTime();
        Stat stat = new Stat();
        long lastId = 0L;
        while (true) {
            List<ShareItem> batch = shareMapper.listItemsByCursor(lastId, batchSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }
            stat.scannedBatches++;
            stat.scannedItems += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            inspectBatch(batch, stat);
            if (batch.size() < batchSize) {
                break;
            }
        }
        report(stat, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * 检查一批 share_item，把孤儿累计进 {@code stat}。
     */
    private void inspectBatch(List<ShareItem> batch, Stat stat) {
        List<Long> fileIds = batch.stream()
                .map(ShareItem::getFileId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        // 一次批量查完本批涉及的 file 状态。
        // ⚠️ 这里若抛异常，必须让整轮对账中断（由 reconcileShareItems 捕获）——
        //    绝不能把「查不到」当成「行不存在」：file-service 抖动时那会把全部条目误报成孤儿
        //    （开了删除开关就是误删）。getShareFileProjectionsWithDeleted 内部会校验上游 code，
        //    非 SUCCESS 一律抛 BusinessException，正是为了守住这条线。
        Map<Long, Integer> statusById = fetchDeleteStatus(fileIds);
        for (ShareItem item : batch) {
            Long fileId = item.getFileId();
            if (fileId == null) {
                continue;
            }
            Integer deleted = statusById.get(fileId);
            if (deleted == null) {
                stat.missingFileCount++;
                recordOrphan(stat, item, "file 行不存在");
            } else if (FileDeleteStatus.DELETED == deleted) {
                stat.deletedFileCount++;
                recordOrphan(stat, item, "已彻底删除");
            } else if (FileDeleteStatus.RECYCLE == deleted) {
                // 回收站：用户随时可还原，分享应随之恢复可用 —— 保留，不清。
                stat.recycleFileCount++;
            }
            // deleted == NORMAL(0)：正常引用，跳过。
        }
    }

    /**
     * 批量取 file 的删除状态。
     * <p>返回集里没有的 id 表示「file 行不存在」—— 这依赖上游实现等价于
     * {@code SELECT ... WHERE id IN (...)} 且不静默丢 id（见
     * {@link ShareFileServiceClient#getShareFileProjectionsWithDeleted(List)} 的契约说明）。</p>
     */
    private Map<Long, Integer> fetchDeleteStatus(List<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        List<ShareFileProjection> projections = fileServiceClient.getShareFileProjectionsWithDeleted(fileIds);
        Map<Long, Integer> statusById = new HashMap<>(Math.max(16, projections.size() * 2));
        for (ShareFileProjection projection : projections) {
            if (projection != null && projection.getId() != null) {
                statusById.put(projection.getId(), projection.getDeleted());
            }
        }
        return statusById;
    }

    private void recordOrphan(Stat stat, ShareItem item, String reason) {
        stat.orphanFileIds.add(item.getFileId());
        if (stat.samples.size() < SAMPLE_LIMIT) {
            stat.samples.add("shareId=" + item.getShareId() + "/fileId=" + item.getFileId() + "(" + reason + ")");
        }
    }

    private void report(Stat stat, long elapsedMs) {
        if (stat.orphanFileIds.isEmpty()) {
            log.info("分享条目对账完成：扫描 {} 条（{} 批），未发现孤儿引用；回收站内文件 {} 条（保留）；耗时 {} ms",
                    stat.scannedItems, stat.scannedBatches, stat.recycleFileCount, elapsedMs);
            return;
        }
        log.warn("分享条目对账发现孤儿引用：扫描 {} 条（{} 批），孤儿 file {} 个"
                        + "（file 行不存在 {} 条 / 已彻底删除 {} 条），回收站内文件 {} 条（保留不清）；耗时 {} ms",
                stat.scannedItems, stat.scannedBatches, stat.orphanFileIds.size(),
                stat.missingFileCount, stat.deletedFileCount, stat.recycleFileCount, elapsedMs);
        if (!stat.samples.isEmpty()) {
            log.warn("孤儿引用样本（最多 {} 条）：{}", SAMPLE_LIMIT, String.join("; ", stat.samples));
        }
        if (!deletionEnabled) {
            log.warn("分享条目对账处于灰度期（app.share.item-reconcile.deletion-enabled=false）："
                    + "本轮仅告警不删除。核对样本与数据量后，再决定是否开启删除");
            return;
        }
        int removed = deleteOrphans(stat.orphanFileIds);
        log.warn("分享条目对账已清理孤儿引用：按 file_id 删除 share_item {} 行", removed);
    }

    /**
     * 按 file_id 删除孤儿引用（仅在 {@code deletion-enabled=true} 时走到）。
     * <p>按 file_id 删而不是按 share_item.id 删：同一个已彻底删除的文件可能被多个分享引用，
     * 按 file_id 一次清掉所有引用才对。分片是因为 {@code IN (...)} 的占位符数量有上限。</p>
     * <p>单片失败只记日志并继续后续分片 —— 已删除的行在下一轮不会再次出现，未删的下一轮会重试。</p>
     */
    private int deleteOrphans(Set<Long> orphanFileIds) {
        List<Long> ids = new ArrayList<>(orphanFileIds);
        int removed = 0;
        for (int from = 0; from < ids.size(); from += batchSize) {
            int to = Math.min(from + batchSize, ids.size());
            try {
                removed += shareMapper.deleteShareItemsByFileIds(ids.subList(from, to));
            } catch (Exception e) {
                log.warn("按 file_id 清理分享条目失败，本分片 fileId 索引区间 [{}, {})，下一轮会重试", from, to, e);
            }
        }
        return removed;
    }

    /**
     * 尝试获取对账锁。
     * <p><b>刻意 fail-open</b>：锁只是「避免多副本重复劳动」的优化，不是正确性前提 ——
     * 对账本身只读+告警，删除用的是幂等 {@code DELETE}。若在这里改成 fail-closed（拿不到锁就跳过），
     * Redis 一次抖动就会把「Redis 故障」静默升级成「对账长期停摆」，那是更难被发现的故障形态。
     * 当前 share-service 是单副本部署，锁的价值主要在将来扩容时。</p>
     */
    private boolean tryAcquireLock(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY, token, lockTtlSeconds, TimeUnit.SECONDS));
        } catch (Exception e) {
            log.warn("分享条目对账获取分布式锁失败，本轮仍继续执行（fail-open）", e);
            return true;
        }
    }

    /**
     * 释放对账锁。
     * <p>必须先比对 token 再删：若本轮执行时间超过了锁 TTL，锁已被别的实例拿走，
     * 此时无条件 {@code delete} 会把别人的锁删掉，反而制造出并发。</p>
     */
    private void releaseLock(String token) {
        try {
            String current = redisTemplate.opsForValue().get(LOCK_KEY);
            if (token.equals(current)) {
                redisTemplate.delete(LOCK_KEY);
            }
        } catch (Exception e) {
            // 释放失败不是问题：TTL 会兜住。
            log.debug("释放分享条目对账锁失败，将等待 TTL 自然过期", e);
        }
    }

    /** 单轮对账的累计统计。 */
    private static final class Stat {
        private int scannedItems;
        private int scannedBatches;
        private int missingFileCount;
        private int deletedFileCount;
        private int recycleFileCount;
        private final Set<Long> orphanFileIds = new LinkedHashSet<>();
        private final List<String> samples = new ArrayList<>();
    }
}
