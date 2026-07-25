package uno.acloud.file.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.Result;
import uno.acloud.exception.BusinessException;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.dto.InternalBatchFileIdsRequest;
import uno.acloud.file.dto.InternalBatchParentIdsRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.vo.FileDownloadUrlVO;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@Hidden
@RestController
@RequestMapping("/api/internal/files")
@Tag(name = "文件管理（内部）", description = "内部服务文件查询 API")
public class InternalFileController {

    private final FileQueryPort fileQueryPort;
    private final StorageProviderRegistry registry;

    public InternalFileController(FileQueryPort fileQueryPort, StorageProviderRegistry registry) {
        this.fileQueryPort = fileQueryPort;
        this.registry = registry;
    }

    @Operation(summary = "批量获取文件信息")
    @PostMapping("/batch-info")
    public Result<List<FileInfoDTO>> getFileInfoByIds(@Valid @RequestBody InternalBatchFileIdsRequest request) {
        return Result.of(fileQueryPort.getFileInfoByIds(request.getFileIds()));
    }

    @Operation(summary = "获取单个文件信息")
    @GetMapping("/{fileId}/info")
    public Result<FileInfoDTO> getFileInfoById(@PathVariable Long fileId) {
        return Result.of(fileQueryPort.getFileInfoById(fileId));
    }

    @Operation(summary = "获取分享子文件列表")
    @GetMapping("/{parentId}/share-children")
    public Result<List<FileInfoDTO>> getShareChildren(@PathVariable Long parentId) {
        return Result.of(fileQueryPort.getShareChildrenByParentIdWithDeleted(parentId));
    }

    @Operation(summary = "批量获取分享子文件列表")
    @PostMapping("/batch-share-children")
    public Result<Map<Long, List<FileInfoDTO>>> getBatchShareChildren(@Valid @RequestBody InternalBatchParentIdsRequest request) {
        return Result.of(fileQueryPort.getShareChildrenByParentIdsWithDeleted(request.getParentIds()));
    }

    @Operation(summary = "获取分享文件下载链接")
    @GetMapping("/{fileId}/share-download-url")
    public Result<FileDownloadUrlVO> getShareDownloadUrl(@PathVariable Long fileId) {
        return Result.of(fileQueryPort.getSharedFileDownloadUrl(fileId));
    }

    @Operation(summary = "获取文件流式下载信息（内部调用）")
    @GetMapping("/{fileId}/stream-info")
    public Result<String> getFileStreamInfo(@PathVariable Long fileId) {
        FileNode fileNode = fileQueryPort.getFileNodeById(fileId);
        if (fileNode == null || !(fileNode instanceof FileItem fileItem)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (provider.supportsPresignedDownload()) {
            return Result.of(provider.generateDownloadInfo(fileItem.getUuidName(), fileItem.getOriginalName()).getDownloadUrl());
        } else {
            return Result.of("/api/files/" + fileId + "/stream");
        }
    }

    @Operation(summary = "流式下载文件（内部调用）")
    @GetMapping("/{fileId}/stream")
    public void streamFile(@PathVariable Long fileId, HttpServletResponse response) {
        FileNode fileNode = fileQueryPort.getFileNodeById(fileId);
        if (fileNode == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        if (!(fileNode instanceof FileItem fileItem)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持文件夹下载");
        }
        StorageProvider provider = registry.resolveForFile(fileItem);
        if (provider.supportsPresignedDownload()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该存储提供者不支持流式下载");
        }
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename*=utf-8''"
                + java.net.URLEncoder.encode(fileItem.getOriginalName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20"));
        try (OutputStream os = response.getOutputStream()) {
            provider.streamDownload(fileItem.getUuidName(), os);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件下载失败");
        }
    }
}
