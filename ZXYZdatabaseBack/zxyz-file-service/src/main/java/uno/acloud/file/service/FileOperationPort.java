package uno.acloud.file.service;

import uno.acloud.file.dto.FileUpdateRequest;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.vo.BatchOperationDetailVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.RenameFileVO;

import java.util.List;

public interface FileOperationPort {

    FileResourceVO patchFile(Long fileId, FileUpdateRequest request, Long userId);

    RenameFileVO renameFile(RenameFileRequest request);

    RenameFileVO renameFile(RenameFileRequest request, Long userId);

    BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long teamId, Long userId);

    BatchOperationDetailVO moveFiles(List<Long> fileIds, Long targetParentId, Long teamId, Integer spaceType, Long projectId, Long userId);

    BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long teamId, Long userId);

    BatchOperationDetailVO copyFiles(List<Long> fileIds, Long targetParentId, Long teamId, Integer spaceType, Long projectId, Long userId);
}
