package uno.acloud.file.service.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FileObjectPhysicalDeleteExecutor {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final long BASE_RETRY_DELAY_SECONDS = 60L;
    private static final long MAX_RETRY_DELAY_SECONDS = 3600L;

    /** 状态更新失败的 last_delete_error 前缀：与「存储删除失败」区分开，便于运维一眼定位是哪种失败 */
    private static final String STATUS_UPDATE_ERROR_PREFIX = "状态更新失败: markDeleted 受影响行数=";

    /** 指标名：物理删除成功次数（作为失败率的分母） */
    private static final String METRIC_DELETED = "file.object.delete.deleted";

    /** 指标名：存储对象已删除，但 markDeleted 未生效 */
    private static final String METRIC_STATUS_UPDATE_FAILED = "file.object.delete.status.update.failed";

    /** 指标名：markDeleted 失败后连回退到 PENDING_DELETE 也失败，需人工介入 */
    private static final String METRIC_STATUS_RECOVERY_FAILED = "file.object.delete.status.recovery.failed";

    /** 指标 tag：存储提供者标识，用于定位是哪个存储后端出问题 */
    private static final String TAG_PROVIDER = "provider";

    private final FileObjectRefMapper fileObjectRefMapper;
    private final StorageProviderRegistry registry;
    private final MeterRegistry meterRegistry;

    public FileObjectPhysicalDeleteExecutor(FileObjectRefMapper fileObjectRefMapper,
                                           StorageProviderRegistry registry,
                                           MeterRegistry meterRegistry) {
        this.fileObjectRefMapper = fileObjectRefMapper;
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    public int deletePendingObjects(int limit) {
        int safeLimit = Math.max(1, limit);
        List<FileObjectRef> pendingRefs = fileObjectRefMapper.listPendingDeletes(
                FileObjectDeleteStatus.PENDING_DELETE,
                safeLimit
        );
        // 按 storage_provider 分组，批量删除时同组对象统一调用
        Map<String, List<FileObjectRef>> groupedByProvider = pendingRefs.stream()
                .collect(Collectors.groupingBy(ref -> StringUtils.trimToNull(ref.getStorageProvider()) != null
                        ? ref.getStorageProvider() : "oss"));
        int successCount = 0;
        for (Map.Entry<String, List<FileObjectRef>> entry : groupedByProvider.entrySet()) {
            StorageProvider provider = registry.getProvider(entry.getKey());
            for (FileObjectRef pendingRef : entry.getValue()) {
                if (deletePendingObject(pendingRef, provider)) {
                    successCount++;
                }
            }
        }
        return successCount;
    }

    private boolean deletePendingObject(FileObjectRef pendingRef, StorageProvider provider) {
        String objectKey = pendingRef == null ? null : StringUtils.trimToNull(pendingRef.getObjectKey());
        if (objectKey == null) {
            return false;
        }
        int claimedRows = fileObjectRefMapper.markDeleting(
                objectKey,
                FileObjectDeleteStatus.PENDING_DELETE,
                FileObjectDeleteStatus.DELETING
        );
        if (claimedRows != 1) {
            return false;
        }
        String providerId = provider.providerId();
        try {
            provider.deleteObject(objectKey);
            int updatedRows = fileObjectRefMapper.markDeleted(
                    objectKey,
                    FileObjectDeleteStatus.DELETING,
                    FileObjectDeleteStatus.DELETED
            );
            if (updatedRows != 1) {
                // 存储对象此刻已经真的删掉了，但 DB 状态没推进。若什么都不做，这一行会永久卡在
                // DELETING：listPendingDeletes 只捞 PENDING_DELETE，deleteExpiredDeleted 只清
                // DELETED，两条清理路径都覆盖不到它，于是变成「无指标、无告警、无对账入口」的
                // 永久僵尸行。这里主动回退到 PENDING_DELETE 送回重试管道：重试时再次
                // deleteObject（OSS 删除已不存在的对象是幂等的）再 markDeleted，直到成功为止。
                incrementCounter(METRIC_STATUS_UPDATE_FAILED, providerId);
                LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(resolveRetryDelaySeconds(pendingRef));
                int recoveredRows = fileObjectRefMapper.markDeleteFailed(
                        objectKey,
                        FileObjectDeleteStatus.DELETING,
                        FileObjectDeleteStatus.PENDING_DELETE,
                        STATUS_UPDATE_ERROR_PREFIX + updatedRows,
                        nextRetryTime
                );
                if (recoveredRows != 1) {
                    // 回退本身也失败（行已被并发改掉或不存在），这是真正需要人工介入的情况
                    incrementCounter(METRIC_STATUS_RECOVERY_FAILED, providerId);
                    log.error("存储对象已删除，删除状态更新失败且回退到待删除也失败，需人工介入，objectKey={}, provider={}, markDeletedRows={}, rollbackRows={}",
                            objectKey, providerId, updatedRows, recoveredRows);
                } else {
                    log.warn("存储对象已删除，但删除状态更新失败，已回退到待删除等待重试，objectKey={}, provider={}, markDeletedRows={}, nextRetryTime={}",
                            objectKey, providerId, updatedRows, nextRetryTime);
                }
                // 本次并未真正走完删除流程，返回 false 才不被计入 successCount
                // （原来这里 return true，等于把失败伪装成成功）
                return false;
            }
            incrementCounter(METRIC_DELETED, providerId);
            log.info("存储对象物理删除成功，objectKey={}, provider={}", objectKey, providerId);
            return true;
        } catch (Exception e) {
            LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(resolveRetryDelaySeconds(pendingRef));
            fileObjectRefMapper.markDeleteFailed(
                    objectKey,
                    FileObjectDeleteStatus.DELETING,
                    FileObjectDeleteStatus.PENDING_DELETE,
                    buildErrorSummary(e),
                    nextRetryTime
            );
            log.warn("存储对象物理删除失败，已进入重试队列，objectKey={}, provider={}, nextRetryTime={}",
                    objectKey, providerId, nextRetryTime, e);
            return false;
        }
    }

    /**
     * 按存储提供者维度递增计数器。
     * <p>
     * Micrometer 以 (name + tags) 作为 MeterId 去重，重复 register 会返回同一个 Counter 实例，
     * 因此这里每次调用 builder 不会造成指标泄漏，也不会重复累加。
     * </p>
     *
     * @param name       指标名
     * @param providerId 存储提供者标识
     */
    private void incrementCounter(String name, String providerId) {
        Counter.builder(name)
                .tag(TAG_PROVIDER, StringUtils.isBlank(providerId) ? "unknown" : providerId)
                .register(meterRegistry)
                .increment();
    }

    private long resolveRetryDelaySeconds(FileObjectRef pendingRef) {
        int retryCount = pendingRef.getDeleteRetryCount() == null ? 0 : Math.max(0, pendingRef.getDeleteRetryCount());
        long multiplier = 1L << Math.min(retryCount, 5);
        return Math.min(MAX_RETRY_DELAY_SECONDS, BASE_RETRY_DELAY_SECONDS * multiplier);
    }

    private String buildErrorSummary(Exception e) {
        String summary = e.getClass().getSimpleName();
        if (StringUtils.isNotBlank(e.getMessage())) {
            summary += ": " + e.getMessage();
        }
        return StringUtils.left(summary, MAX_ERROR_LENGTH);
    }
}
