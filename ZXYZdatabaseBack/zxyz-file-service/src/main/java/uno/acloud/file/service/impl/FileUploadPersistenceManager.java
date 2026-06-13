package uno.acloud.file.service.impl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.mapper.FileMapper;

@Component
public class FileUploadPersistenceManager {

    private final FileMapper fileMapper;
    private final FileObjectReferenceManager fileObjectReferenceService;

    public FileUploadPersistenceManager(FileMapper fileMapper,
                                        FileObjectReferenceManager fileObjectReferenceService) {
        this.fileMapper = fileMapper;
        this.fileObjectReferenceService = fileObjectReferenceService;
    }

    @Transactional(rollbackFor = Exception.class)
    public FileItem saveFileItem(FileItem fileItem) {
        Integer insertedRows = fileMapper.insertFileItem(fileItem);
        if (insertedRows == null || insertedRows != 1 || fileItem.getId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "保存文件信息失败");
        }
        fileObjectReferenceService.retainReference(fileItem.getUuidName());
        return fileItem;
    }
}
