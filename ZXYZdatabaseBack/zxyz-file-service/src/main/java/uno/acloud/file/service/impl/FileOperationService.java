package uno.acloud.file.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.FileUpdateRequest;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.service.FileOperationPort;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.vo.BatchOperationDetailVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.RenameFileVO;

import java.util.List;

/**
 * Thin facade that delegates file operations to dedicated services.
 */
@Service
public class FileOperationService implements FileOperationPort {

    private final FileRenameService fileRenameService;
    private final FileMoveService fileMoveService;
    private final FileCopyService fileCopyService;
    private final FileOperationHelper helper;
    private final FileQueryPort fileQueryPort;
    private final FileDomainValidator fileDomainValidator;

    public FileOperationService(FileRenameService fileRenameService,
                                FileMoveService fileMoveService,
                                FileCopyService fileCopyService,
                                FileOperationHelper helper,
                                FileQueryPort fileQueryPort,
                                FileDomainValidator fileDomainValidator) {
        this.fileRenameService = fileRenameService;
        this.fileMoveService = fileMoveService;
        this.fileCopyService = fileCopyService;
        this.helper = helper;
        this.fileQueryPort = fileQueryPort;
        this.fileDomainValidator = fileDomainValidator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileResourceVO patchFile(Long fileId, FileUpdateRequest request, Long userId) {
        boolean hasNewName = request != null && request.getNewName() != null && !request.getNewName().isBlank();
        boolean hasTargetParentId = request != null && request.getTargetParentId() != null;
        if (hasNewName == hasTargetParentId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "newName 和 targetParentId 必须且只能传一个");
        }

        if (hasNewName) {
            RenameFileRequest renameRequest = new RenameFileRequest();
            renameRequest.setFileId(fileId);
            renameRequest.setNewName(request.getNewName());
            fileRenameService.renameFile(renameRequest, userId);
        } else {
            BatchOperationDetailVO result = fileMoveService.moveFiles(
                    List.of(fileId), request.getTargetParentId(), request.getTeamId(), userId);
            if (result.getSuccessCount() <= 0 && result.getSkippedCount() <= 0) {
                throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "文件移动失败");
            }
        }

        FileResourceVO fileResource = fileQueryPort.getFileResourceById(fileId, userId);
        if (fileResource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return fileResource;
    }

    @Override
    public RenameFileVO renameFile(RenameFileRequest request) {
        return fileRenameService.renameFile(request);
    }

    @Override
    public RenameFileVO renameFile(RenameFileRequest request, Long userId) {
        return fileRenameService.renameFile(request, userId);
    }

    @Override
    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long teamId, Long userId) {
        return fileMoveService.moveFiles(fileIds, targetParentId, teamId, userId);
    }

    @Override
    public BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long teamId,
                                            Integer spaceType, Long projectId, Long userId) {
        return fileMoveService.moveFiles(fileIds, targetParentId, teamId, spaceType, projectId, userId);
    }

    @Override
    public BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long teamId, Long userId) {
        return fileCopyService.copyFiles(fileIds, targetParentId, teamId, userId);
    }

    @Override
    public BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long teamId,
                                            Integer spaceType, Long projectId, Long userId) {
        return fileCopyService.copyFiles(fileIds, targetParentId, teamId, spaceType, projectId, userId);
    }
}
