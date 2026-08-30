package uno.acloud.file.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.UsageLedger;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

@Component
public class FileUploadPersistenceManager {

    private final FileMapper fileMapper;
    private final FileObjectReferenceManager fileObjectReferenceService;
    private final UsageLedgerMapper usageLedgerMapper;

    public FileUploadPersistenceManager(FileMapper fileMapper,
                                        FileObjectReferenceManager fileObjectReferenceService,
                                        UsageLedgerMapper usageLedgerMapper) {
        this.fileMapper = fileMapper;
        this.fileObjectReferenceService = fileObjectReferenceService;
        this.usageLedgerMapper = usageLedgerMapper;
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
        return fileItem;
    }
}
