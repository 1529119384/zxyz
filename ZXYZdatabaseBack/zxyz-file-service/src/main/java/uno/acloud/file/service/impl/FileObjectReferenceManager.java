package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class FileObjectReferenceManager {

    private final FileObjectRefMapper fileObjectRefMapper;

    public FileObjectReferenceManager(FileObjectRefMapper fileObjectRefMapper) {
        this.fileObjectRefMapper = fileObjectRefMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void retainReference(String objectKey, String storageProvider) {
        String normalizedKey = normalizeObjectKey(objectKey);
        if (normalizedKey == null) {
            return;
        }
        if (storageProvider == null || storageProvider.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "storageProvider 不能为空");
        }
        int updatedRows = fileObjectRefMapper.incrementReference(normalizedKey, 1, FileObjectDeleteStatus.ACTIVE, storageProvider.trim());
        if (updatedRows <= 0) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件对象引用计数增加失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void retainReference(String objectKey) {
        retainReference(objectKey, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void releaseReferences(Collection<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        Map<String, Long> releaseCountByKey = objectKeys.stream()
                .map(this::normalizeObjectKey)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.groupingBy(key -> key, Collectors.counting()));
        for (Map.Entry<String, Long> entry : releaseCountByKey.entrySet()) {
            releaseReference(entry.getKey(), entry.getValue());
        }
    }

    private void releaseReference(String objectKey, long releaseCount) {
        if (releaseCount <= 0 || releaseCount > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件对象引用计数释放数量非法");
        }
        int updatedRows = fileObjectRefMapper.decrementReference(
                objectKey,
                Math.toIntExact(releaseCount),
                FileObjectDeleteStatus.ACTIVE
        );
        if (updatedRows == 1) {
            fileObjectRefMapper.markPendingIfUnused(
                    objectKey,
                    FileObjectDeleteStatus.ACTIVE,
                    FileObjectDeleteStatus.PENDING_DELETE
            );
            return;
        }
        FileObjectRef ref = fileObjectRefMapper.selectByKey(objectKey);
        if (ref == null) {
            log.warn("文件对象引用行不存在，跳过释放 objectKey={}", objectKey);
            return;
        }
        if (FileObjectDeleteStatus.PENDING_DELETE.equals(ref.getDeleteStatus())
                || FileObjectDeleteStatus.DELETING.equals(ref.getDeleteStatus())
                || FileObjectDeleteStatus.DELETED.equals(ref.getDeleteStatus())) {
            log.info("文件对象引用已在删除流程中，跳过释放 objectKey={}, status={}", objectKey, ref.getDeleteStatus());
            return;
        }
        throw new BusinessException(ErrorCode.FILE_STATE_INVALID,
                "文件对象引用计数不足，objectKey=" + objectKey + ", refCount=" + ref.getRefCount());
    }

    private String normalizeObjectKey(String objectKey) {
        return StringUtils.trimToNull(objectKey);
    }
}
