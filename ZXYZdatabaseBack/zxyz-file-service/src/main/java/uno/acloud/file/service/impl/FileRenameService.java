package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.RenameFileVO;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class FileRenameService {

    private final FileMapper fileMapper;
    private final StorageProviderRegistry registry;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final FileOperationHelper helper;
    private final FileResourceChangedPublisher fileResourceChangedPublisher;

    public FileRenameService(FileMapper fileMapper,
                             StorageProviderRegistry registry,
                             FileDomainValidator fileDomainValidator,
                             FilePathResolver filePathResolver,
                             FileAccessGuard fileAccessGuardService,
                             FileOperationHelper helper,
                             Optional<FileResourceChangedPublisher> fileResourceChangedPublisher) {
        this.fileMapper = fileMapper;
        this.registry = registry;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.helper = helper;
        this.fileResourceChangedPublisher = fileResourceChangedPublisher.orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public RenameFileVO renameFile(RenameFileRequest request) {
        return renameFile(request, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public RenameFileVO renameFile(RenameFileRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求不能为空");
        }
        if (request.getFileId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileId 不能为空");
        }

        String normalizedNewName = validateRenameName(request.getNewName());
        FileNode fileNode = fileDomainValidator.requireNodeForRename(request.getFileId());
        fileAccessGuardService.requireWriteAccess(fileNode, userId);
        String finalOriginalName = buildRenamedOriginalName(fileNode, normalizedNewName);
        validateFinalOriginalName(finalOriginalName);
        String newStorePath = filePathResolver.buildStorePath(fileNode.getParentId(), finalOriginalName);

        if (fileNode instanceof FileItem fileItem) {
            renameFileNode(fileItem, finalOriginalName, newStorePath);
        } else if (fileNode instanceof Folder folder) {
            renameFolderTree(folder, finalOriginalName, newStorePath);
        } else {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "非法的文件节点类型");
        }

        RenameFileVO response = new RenameFileVO(
                fileNode.getId(),
                finalOriginalName,
                fileNode.getFileType(),
                fileNode.getParentId(),
                LocalDateTime.now()
        );
        helper.publishByIdsAfterCommit("RENAMED", List.of(fileNode.getId()));
        return response;
    }

    private String validateRenameName(String newName) {
        return fileDomainValidator.validateInputName(newName);
    }

    private void validateFinalOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        if (originalName.length() > 255) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名长度不能超过 255");
        }
    }

    private String buildRenamedOriginalName(FileNode fileNode, String newName) {
        if (!(fileNode instanceof FileItem fileItem)) {
            return newName;
        }
        String extension = extractStandardExtension(fileItem.getOriginalName());
        if (extension.isEmpty()) {
            return newName;
        }
        return newName + "." + extension;
    }

    private String extractStandardExtension(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "";
        }
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(dotIndex + 1);
    }

    private void renameFileNode(FileItem fileItem, String finalOriginalName, String newStorePath) {
        int updatedRows = fileMapper.renameNodeById(fileItem.getId(), finalOriginalName, newStorePath);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重命名文件失败");
        }
        String uuidName = fileItem.getUuidName();
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        provider.updateContentDisposition(uuidName, finalOriginalName);
                    } catch (Exception e) {
                        log.warn("Failed to update content disposition after rename for uuidName={}: {}", uuidName, e.getMessage());
                    }
                }
            });
        } else {
            provider.updateContentDisposition(uuidName, finalOriginalName);
        }
    }

    private void renameFolderTree(Folder folder, String finalOriginalName, String newStorePath) {
        String oldStorePath = folder.getStorePath();
        int updatedRows = fileMapper.renameNodeById(folder.getId(), finalOriginalName, newStorePath);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "重命名文件夹失败");
        }
        fileMapper.renameDescendantStorePaths(oldStorePath, newStorePath);
    }
}
