package uno.acloud.file.service.impl;

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

    private final FileObjectRefMapper fileObjectRefMapper;
    private final StorageProviderRegistry registry;

    public FileObjectPhysicalDeleteExecutor(FileObjectRefMapper fileObjectRefMapper,
                                           StorageProviderRegistry registry) {
        this.fileObjectRefMapper = fileObjectRefMapper;
        this.registry = registry;
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
        try {
            provider.deleteObject(objectKey);
            int updatedRows = fileObjectRefMapper.markDeleted(
                    objectKey,
                    FileObjectDeleteStatus.DELETING,
                    FileObjectDeleteStatus.DELETED
            );
            if (updatedRows != 1) {
                log.warn("存储对象已删除，但删除状态更新失败，objectKey={}", objectKey);
            }
            log.info("存储对象物理删除成功，objectKey={}, provider={}", objectKey, provider.providerId());
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
                    objectKey, provider.providerId(), nextRetryTime, e);
            return false;
        }
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
