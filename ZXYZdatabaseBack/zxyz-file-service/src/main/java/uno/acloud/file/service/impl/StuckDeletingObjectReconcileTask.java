package uno.acloud.file.service.impl;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 卡在 DELETING 状态的对象对账任务（P0-9 兜底）。
 * <p>
 * 物理删除的执行顺序是「先删存储对象，再更新 DB 状态」（见
 * {@link FileObjectPhysicalDeleteExecutor}），中间存在一段无法通过异常捕获兜住的窗口：
 * <ol>
 *   <li>deleteObject 成功 → markDeleted 返回 0：已由执行器自愈（回退到 PENDING_DELETE
 *       重新入队重试）；</li>
 *   <li>deleteObject 成功 → <b>进程崩溃</b> → markDeleted 从未执行：没有任何代码路径能
 *       再拾取这一行，它永久停在 DELETING。</li>
 * </ol>
 * listPendingDeletes 只捞 PENDING_DELETE，deleteExpiredDeleted 只清 DELETED，两者都
 * 覆盖不到 DELETING，因此第 2 种情况是彻底不可见的存储泄漏——这正是本任务存在的意义。
 * </p>
 * <p>
 * <b>取舍：只告警，不自动改状态。</b> 卡在 DELETING 时我们无法确知存储对象到底删没删：
 * 若擅自标记为 DELETED，一旦对象其实还在 OSS 上，这笔泄漏就再也没人知道（且
 * deleteExpiredDeleted 会在 30 天后把唯一的线索删掉）。反之保留 DELETING 并大声告警，
 * 至少留住了可人工核对的现场。因此在人工确认存储侧真实情况之前，本任务不做任何写操作。
 * </p>
 * <p>
 * 另注：本任务<u>不</u>跟随 {@code app.file-object-delete.enabled} 开关（对比
 * {@link OrphanObjectReconcileTask} 会跳过）——它是纯观测任务，恰恰在删除功能被关闭期间
 * 更需要看见历史遗留的卡死行。
 * </p>
 */
@Slf4j
@Component
public class StuckDeletingObjectReconcileTask {

    /**
     * DELETING 卡死判定阈值（分钟）。
     * <p>
     * 需远大于一次删除操作的合理耗时（一次 deleteObject + 一次 UPDATE，正常在毫秒级，
     * 最坏情况数十秒）。取 1 小时可确保不会把正在执行中的删除误判为卡死。
     * </p>
     */
    private static final long STUCK_THRESHOLD_MINUTES = 60L;

    /** 单次扫描条数上限，避免极端情况下一次性拉出海量行 */
    private static final int SCAN_LIMIT = 200;

    /** 指标名：当前卡在 DELETING 的行数（Gauge，便于直接配告警） */
    private static final String METRIC_STUCK = "file.object.delete.stuck.deleting";

    private final FileObjectRefMapper fileObjectRefMapper;

    /** 最近一次扫描到的卡死行数，供 Gauge 回读；受 SCAN_LIMIT 截断，实际值可能更大 */
    private final AtomicInteger lastStuckCount = new AtomicInteger(0);

    public StuckDeletingObjectReconcileTask(FileObjectRefMapper fileObjectRefMapper,
                                            MeterRegistry meterRegistry) {
        this.fileObjectRefMapper = fileObjectRefMapper;
        Gauge.builder(METRIC_STUCK, lastStuckCount, AtomicInteger::get)
                .description("长期卡在 DELETING 状态（modify_time 超过 1 小时未推进）的 file_object_ref 行数")
                .register(meterRegistry);
    }

    /**
     * 每小时扫描一次卡在 DELETING 的行，仅告警不修复。异常不影响调度，仅记日志。
     */
    @Scheduled(fixedRate = 3600000)
    public void reconcileStuckDeleting() {
        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(STUCK_THRESHOLD_MINUTES);
            List<FileObjectRef> stuckRefs = fileObjectRefMapper.listStuckDeleting(
                    FileObjectDeleteStatus.DELETING, cutoffTime, SCAN_LIMIT);
            if (stuckRefs == null || stuckRefs.isEmpty()) {
                lastStuckCount.set(0);
                log.debug("DELETING 卡死对账完成，无卡死记录（阈值={}分钟）", STUCK_THRESHOLD_MINUTES);
                return;
            }
            // 受 SCAN_LIMIT 截断，实际卡死数量可能 >= 该值
            lastStuckCount.set(stuckRefs.size());
            log.error("发现长期卡在 DELETING 状态的对象，数量={}（阈值={}分钟，单次扫描上限={}），" +
                            "需人工核对存储对象是否已真实删除后再决定处理方式",
                    stuckRefs.size(), STUCK_THRESHOLD_MINUTES, SCAN_LIMIT);
            for (FileObjectRef ref : stuckRefs) {
                log.error("卡在 DELETING 的 objectKey={}, storageProvider={}, refCount={}, deleteRetryCount={}, " +
                                "modifyTime={}, nextRetryTime={}, lastDeleteError={}",
                        ref.getObjectKey(),
                        StringUtils.defaultIfBlank(ref.getStorageProvider(), "unknown"),
                        ref.getRefCount(),
                        ref.getDeleteRetryCount(),
                        ref.getModifyTime(),
                        ref.getNextRetryTime(),
                        ref.getLastDeleteError());
            }
        } catch (Exception e) {
            log.warn("DELETING 卡死对象对账任务异常", e);
        }
    }
}
