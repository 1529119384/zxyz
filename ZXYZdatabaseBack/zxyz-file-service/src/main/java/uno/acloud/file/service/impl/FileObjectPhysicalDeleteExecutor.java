package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.infrastructure.oss.OSSDeleter;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
public class FileObjectPhysicalDeleteExecutor {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final long BASE_RETRY_DELAY_SECONDS = 60L;
    private static final long MAX_RETRY_DELAY_SECONDS = 3600L;

    private final FileObjectRefMapper fileObjectRefMapper;
    private final OSSDeleter ossDeleter;

    public FileObjectPhysicalDeleteExecutor(FileObjectRefMapper fileObjectRefMapper,
                                           OSSDeleter ossDeleter) {
        this.fileObjectRefMapper = fileObjectRefMapper;
        this.ossDeleter = ossDeleter;
    }

    public int deletePendingObjects(int limit) {
        int safeLimit = Math.max(1, limit);
        List<FileObjectRef> pendingRefs = fileObjectRefMapper.listPendingDeletes(
                FileObjectDeleteStatus.PENDING_DELETE,
                safeLimit
        );
        int successCount = 0;
        for (FileObjectRef pendingRef : pendingRefs) {
            if (deletePendingObject(pendingRef)) {
                successCount++;
            }
        }
        return successCount;
    }

    private boolean deletePendingObject(FileObjectRef pendingRef) {
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
            ossDeleter.delete(objectKey);
            int updatedRows = fileObjectRefMapper.markDeleted(
                    objectKey,
                    FileObjectDeleteStatus.DELETING,
                    FileObjectDeleteStatus.DELETED
            );
            if (updatedRows != 1) {
                log.warn("OSS 对象已删除，但删除状态更新失败，objectKey={}", objectKey);
            }
            log.info("OSS 对象物理删除成功，objectKey={}", objectKey);
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
            log.warn("OSS 对象物理删除失败，已进入重试队列，objectKey={}, nextRetryTime={}",
                    objectKey, nextRetryTime, e);
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
