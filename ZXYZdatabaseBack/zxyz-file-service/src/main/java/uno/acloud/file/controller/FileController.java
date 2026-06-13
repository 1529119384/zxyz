package uno.acloud.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.common.audit.Log;
import uno.acloud.common.permission.RequiresTeamPermission;
import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.dto.BatchFileRequest;
import uno.acloud.file.dto.FileUpdateRequest;
import uno.acloud.file.dto.HasFileIds;
import uno.acloud.file.dto.MoveCopyFilesRequest;
import uno.acloud.file.service.FileLifecyclePort;
import uno.acloud.file.service.FileOperationPort;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.FileUploadPort;
import uno.acloud.file.vo.BatchOperationDetailVO;
import uno.acloud.file.vo.BatchOperationResult;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.vo.FileDownloadUrlVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.FileSearchResultVO;
import uno.acloud.common.oss.OssSignInfo;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/files")
@Tag(name = "文件管理", description = "文件上传、下载、移动、删除")
public class FileController {

    private final FileUploadPort fileUploadPort;
    private final FileQueryPort fileQueryPort;
    private final FileOperationPort fileOperationPort;
    private final FileLifecyclePort fileLifecyclePort;

    public FileController(FileUploadPort fileUploadPort, FileQueryPort fileQueryPort,
                          FileOperationPort fileOperationPort, FileLifecyclePort fileLifecyclePort) {
        this.fileUploadPort = fileUploadPort;
        this.fileQueryPort = fileQueryPort;
        this.fileOperationPort = fileOperationPort;
        this.fileLifecyclePort = fileLifecyclePort;
    }

    @Operation(summary = "获取文件上传签名")
    @Log
    @PostMapping("/uploads")
    @SaCheckPermission(SystemPermissionCodes.FILE_UPLOAD)
    public Result<OssSignInfo> getUploadSign(@CurrentUser Long userId, @RequestParam String originalName) {
        log.info("用户 {} 请求获取上传签名，原始文件名: {}", userId, originalName);
        OssSignInfo signInfo = fileUploadPort.getUploadSign(originalName);
        return Result.of(signInfo);
    }

    @Operation(summary = "批量确认文件上传")
    @Log
    @PostMapping("/uploads/confirmations")
    @SaCheckPermission(SystemPermissionCodes.FILE_UPLOAD)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_WRITE, teamIdArg = "request.teamId")
    public Result<BatchUploadConfirmResultVO> confirmUpload(@CurrentUser Long userId, @Valid @RequestBody BatchConfirmUploadRequest request) {
        int fileCount = request == null || request.getFiles() == null ? 0 : request.getFiles().size();
        log.info("用户 {} 批量确认上传文件，数量: {}", userId, fileCount);
        BatchUploadConfirmResultVO result = fileUploadPort.confirmUpload(request, userId);
        return Result.of(result);
    }

    @Operation(summary = "获取文件列表")
    @Log
    @GetMapping
    @SaCheckPermission(SystemPermissionCodes.FILE_READ)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_READ)
    public Result<List<FileListItemVO>> getFileList(@CurrentUser Long userId,
                              @RequestParam Long parentId,
                              @RequestParam(required = false) Long teamId,
                              @RequestParam(required = false) Integer spaceType,
                              @RequestParam(required = false) Long projectId,
                              @RequestParam(required = false) String sortField,
                              @RequestParam(required = false) String sortOrder) {
        return Result.of(fileQueryPort.getFileListByParentId(parentId, teamId, spaceType, projectId, sortField, sortOrder, userId));
    }

    @Operation(summary = "获取文件详情")
    @GetMapping("/{fileId}")
    @SaCheckPermission(SystemPermissionCodes.FILE_READ)
    public Result<FileResourceVO> getFile(@CurrentUser Long userId, @PathVariable Long fileId) {
        return Result.of(requireFileResource(fileId, userId));
    }

    @Operation(summary = "搜索文件")
    @GetMapping("/search")
    @SaCheckPermission(SystemPermissionCodes.FILE_READ)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_READ)
    public Result<FileSearchResultVO> searchFiles(@CurrentUser Long userId,
                              @RequestParam String keyword,
                              @RequestParam(required = false) Long teamId,
                              @RequestParam(required = false) Integer spaceType,
                              @RequestParam(required = false) Long projectId,
                              @RequestParam(defaultValue = "1") Integer page,
                              @RequestParam(defaultValue = "20") Integer pageSize) {
        FileSearchResultVO result = spaceType == null && projectId == null
                ? fileQueryPort.searchFiles(keyword, page, pageSize, userId, teamId)
                : fileQueryPort.searchFiles(keyword, page, pageSize, userId, teamId, spaceType, projectId);
        return Result.of(result);
    }

    @Operation(summary = "获取文件下载链接")
    @Log
    @GetMapping("/{fileId}/download-url")
    @SaCheckPermission(SystemPermissionCodes.FILE_READ)
    public Result<FileDownloadUrlVO> getFileDownloadUrl(@CurrentUser Long userId, @PathVariable Long fileId) {
        FileDownloadUrlVO downloadUrl = fileQueryPort.getFileDownloadUrl(fileId, userId);
        return Result.of(downloadUrl);
    }

    @Operation(summary = "更新文件信息")
    @PatchMapping(path = "/{fileId}", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_WRITE)
    public Result<FileResourceVO> patchFile(@CurrentUser Long userId, @PathVariable Long fileId, @Valid @RequestBody FileUpdateRequest request) {
        return Result.of(fileOperationPort.patchFile(fileId, request, userId));
    }

    @Operation(summary = "批量移动文件")
    @PatchMapping(path = "", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_WRITE)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_WRITE, teamIdArg = "request.teamId")
    public Result<BatchOperationDetailVO> patchFiles(@CurrentUser Long userId, @Valid @RequestBody MoveCopyFilesRequest request) {
        List<Long> fileIds = resolveFileIds(request);
        Long targetParentId = resolveTargetParentId(request);
        if (request.getSpaceType() == null && request.getProjectId() == null) {
            return Result.of(fileOperationPort.moveFiles(fileIds, targetParentId, request.getTeamId(), userId));
        }
        return Result.of(fileOperationPort.moveFiles(fileIds, targetParentId, request.getTeamId(),
                request.getSpaceType(), request.getProjectId(), userId));
    }

    @Operation(summary = "批量复制文件")
    @PostMapping(path = "/copies", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_WRITE)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_WRITE, teamIdArg = "request.teamId")
    public Result<BatchOperationDetailVO> copyFiles(@CurrentUser Long userId, @Valid @RequestBody MoveCopyFilesRequest request) {
        List<Long> fileIds = resolveFileIds(request);
        Long targetParentId = resolveTargetParentId(request);
        if (request.getSpaceType() == null && request.getProjectId() == null) {
            return Result.of(fileOperationPort.copyFiles(fileIds, targetParentId, request.getTeamId(), userId));
        }
        return Result.of(fileOperationPort.copyFiles(fileIds, targetParentId, request.getTeamId(),
                request.getSpaceType(), request.getProjectId(), userId));
    }

    @Operation(summary = "将文件移入回收站")
    @PatchMapping("/{fileId}/trash")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> moveFileToTrash(@CurrentUser Long userId, @PathVariable Long fileId) {
        int successCount = fileLifecyclePort.logicalDelete(List.of(fileId), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    @Operation(summary = "批量移入回收站")
    @PatchMapping(path = "/trash", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> moveFilesToTrash(@CurrentUser Long userId, @Valid @RequestBody BatchFileRequest request) {
        int successCount = fileLifecyclePort.logicalDelete(resolveFileIds(request), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    @Operation(summary = "从回收站恢复文件")
    @DeleteMapping("/{fileId}/trash")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> restoreFile(@CurrentUser Long userId, @PathVariable Long fileId) {
        int successCount = fileLifecyclePort.restoreFiles(List.of(fileId), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    @Operation(summary = "批量从回收站恢复文件")
    @DeleteMapping(path = "/trash", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> restoreFiles(@CurrentUser Long userId, @Valid @RequestBody BatchFileRequest request) {
        int successCount = fileLifecyclePort.restoreFiles(resolveFileIds(request), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    @Operation(summary = "永久删除文件")
    @DeleteMapping("/{fileId}")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> deleteFile(@CurrentUser Long userId, @PathVariable Long fileId) {
        int successCount = fileLifecyclePort.reallyDelete(List.of(fileId), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    @Operation(summary = "批量永久删除文件")
    @DeleteMapping(path = "", consumes = "application/json")
    @SaCheckPermission(SystemPermissionCodes.FILE_DELETE)
    public Result<BatchOperationResult> deleteFiles(@CurrentUser Long userId, @Valid @RequestBody BatchFileRequest request) {
        int successCount = fileLifecyclePort.reallyDelete(resolveFileIds(request), userId);
        return Result.of(new BatchOperationResult(successCount));
    }

    private FileResourceVO requireFileResource(Long fileId, Long userId) {
        FileResourceVO fileResource = fileQueryPort.getFileResourceById(fileId, userId);
        if (fileResource == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return fileResource;
    }

    private List<Long> resolveFileIds(HasFileIds request) {
        if (request != null && request.getFileIds() != null && !request.getFileIds().isEmpty()) {
            return request.getFileIds();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "fileIds 不能为空");
    }

    private Long resolveTargetParentId(MoveCopyFilesRequest request) {
        if (request != null && request.getTargetParentId() != null) {
            return request.getTargetParentId();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "targetParentId 不能为空");
    }
}
