package uno.acloud.file.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.file.dto.InternalBatchFileIdsRequest;
import uno.acloud.file.dto.InternalBatchParentIdsRequest;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.vo.FileDownloadUrlVO;

import java.util.List;
import java.util.Map;

@Hidden
@RestController
@RequestMapping("/api/internal/files")
@Tag(name = "文件管理（内部）", description = "内部服务文件查询 API")
public class InternalFileController {

    private final FileQueryPort fileQueryPort;

    public InternalFileController(FileQueryPort fileQueryPort) {
        this.fileQueryPort = fileQueryPort;
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
}
