package uno.acloud.file.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.UsageLedger;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;
import uno.acloud.file.util.TransactionUtils;

import java.util.List;
import java.util.Optional;

@Component
public class FileUploadPersistenceManager {

    /** 上传落库产生的资源变更事件类型；与 DELETED/RESTORED/MOVED/RENAMED 同一套词表。 */
    public static final String EVENT_TYPE_CREATED = "CREATED";

    private final FileMapper fileMapper;
    private final FileObjectReferenceManager fileObjectReferenceService;
    private final UsageLedgerMapper usageLedgerMapper;
    private final StorageCacheService storageCacheService;
    private final FileResourceChangedPublisher fileResourceChangedPublisher;

    public FileUploadPersistenceManager(FileMapper fileMapper,
                                        FileObjectReferenceManager fileObjectReferenceService,
                                        UsageLedgerMapper usageLedgerMapper,
                                        StorageCacheService storageCacheService,
                                        Optional<FileResourceChangedPublisher> fileResourceChangedPublisher) {
        this.fileMapper = fileMapper;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.usageLedgerMapper = usageLedgerMapper;
        this.storageCacheService = storageCacheService;
        this.fileResourceChangedPublisher = fileResourceChangedPublisher.orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public FileItem saveFileItem(FileItem fileItem) {
        Integer insertedRows = fileMapper.insertFileItem(fileItem);
        if (insertedRows == null || insertedRows != 1 || fileItem.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "保存文件信息失败");
        }
        fileObjectReferenceService.retainReference(fileItem.getUuidName(), fileItem.getStorageProvider());
        // P2-C2 配额台账：与文件落库同一事务原子扣减。"检查与扣减原子化"，超限则整体回滚拒绝。
        // 预检阶段已把存储上限写入台账的 storage_limit；此处 ensure 兜底行缺失（配额服务未配置时 limit 为 NULL=不限制）。
        String scopeKey = UsageLedger.scopeKeyOf(fileItem.getSpaceType(), fileItem.getTeamId(), fileItem.getProjectId(), fileItem.getUploadUserId());
        usageLedgerMapper.ensureScopeAndLimit(scopeKey, null);
        long fileBytes = fileItem.getFileSize() == null ? 0L : fileItem.getFileSize();
        int affected = usageLedgerMapper.incrementWhenUnderLimit(scopeKey, fileBytes);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传超过当前空间配额，请清理后重试");
        }
        // 新文件已入账 ⇒ 存储用量口径变化，提交后必须失效两层用量缓存（否则前端容量条停留在旧值，
        // 用户报障：「上传后没有及时刷新大小」）。与删除/恢复/复制/移动保持同一策略：
        // 失效 file-service 的 StorageCacheService + 广播 file.resource.changed 驱动 project-service 侧失效。
        invalidateUsageCachesAfterCommit(fileItem);
        return fileItem;
    }

    /**
     * 注册提交后动作。刻意走 {@link TransactionUtils#runAfterCommit}：
     * 事务提交后才失效，避免"读旧值又写回缓存"的竞态；该工具在无事务时同步执行且**吞掉异常**，
     * 因此 Redis/MQ 抖动不会把一次成功的上传变成 500（缓存 30s 自然过期兜底）。
     */
    private void invalidateUsageCachesAfterCommit(FileItem fileItem) {
        TransactionUtils.runAfterCommit(() -> storageCacheService.invalidateAllStorageCaches());
        if (fileResourceChangedPublisher == null) {
            return;
        }
        Long newFileId = fileItem.getId();
        if (newFileId == null) {
            return;
        }
        List<Long> fileIds = List.of(newFileId);
        TransactionUtils.runAfterCommit(() -> fileResourceChangedPublisher.publishByIds(EVENT_TYPE_CREATED, fileIds));
    }
}
