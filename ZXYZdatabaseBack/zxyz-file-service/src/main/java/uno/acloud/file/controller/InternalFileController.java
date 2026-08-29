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
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.controller.model.ShareFileProjectionVO;
import uno.acloud.file.dto.InternalBatchFileIdsRequest;
import uno.acloud.file.dto.InternalBatchParentIdsRequest;
import uno.acloud.file.dto.InternalShareAccessCheckRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.impl.FileAccessGuard;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Hidden
@RestController
@RequestMapping("/api/internal/files")
@Tag(name = "文件管理（内部）", description = "内部服务文件查询 API")
public class InternalFileController {

    private final FileQueryPort fileQueryPort;
    private final StorageProviderRegistry registry;
    private final FileAccessGuard fileAccessGuard;

    public InternalFileController(FileQueryPort fileQueryPort,
                                  StorageProviderRegistry registry,
                                  FileAccessGuard fileAccessGuard) {
        this.fileQueryPort = fileQueryPort;
        this.registry = registry;
        this.fileAccessGuard = fileAccessGuard;
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

    // ==================== Share 窄投影端点 ====================

    @Operation(summary = "获取文件 share 投影")
    @GetMapping("/{fileId}/share-projection")
    public Result<ShareFileProjectionVO> getShareProjection(@PathVariable Long fileId) {
        List<FileNode> nodes = fileQueryPort.getActiveFileNodesByIds(List.of(fileId));
        if (nodes.isEmpty() || nodes.get(0) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return Result.of(toShareProjectionVO(nodes.get(0)));
    }

    @Operation(summary = "批量获取文件 share 投影")
    @PostMapping("/batch-share-projection")
    public Result<List<ShareFileProjectionVO>> getBatchShareProjection(@Valid @RequestBody InternalBatchFileIdsRequest request) {
        List<FileNode> nodes = fileQueryPort.getActiveFileNodesByIds(request.getFileIds());
        return Result.of(nodes.stream()
                .filter(Objects::nonNull)
                .map(this::toShareProjectionVO)
                .toList());
    }

    @Operation(summary = "获取分享子文件列表（窄投影）")
    @GetMapping("/{parentId}/share-children-projection")
    public Result<List<ShareFileProjectionVO>> getShareChildrenProjection(@PathVariable Long parentId) {
        List<FileInfoDTO> fileInfos = fileQueryPort.getShareChildrenByParentIdWithDeleted(parentId);
        return Result.of(fileInfos.stream()
                .filter(Objects::nonNull)
                .map(this::toShareProjectionVO)
                .toList());
    }

    @Operation(summary = "批量获取分享子文件列表（窄投影）")
    @PostMapping("/batch-share-children-projection")
    public Result<Map<Long, List<ShareFileProjectionVO>>> getBatchShareChildrenProjection(@Valid @RequestBody InternalBatchParentIdsRequest request) {
        Map<Long, List<FileInfoDTO>> result = fileQueryPort.getShareChildrenByParentIdsWithDeleted(request.getParentIds());
        Map<Long, List<ShareFileProjectionVO>> projected = new HashMap<>();
        result.forEach((parentId, infos) -> {
            List<ShareFileProjectionVO> list = infos.stream()
                    .filter(Objects::nonNull)
                    .map(this::toShareProjectionVO)
                    .toList();
            projected.put(parentId, list);
        });
        return Result.of(projected);
    }

    @Operation(summary = "创建分享前校验文件归属与读权限（P0-3，内部调用）")
    @PostMapping("/share-access-check")
    public Result<Void> checkShareFileAccess(@Valid @RequestBody InternalShareAccessCheckRequest request) {
        if (request.getUserId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        List<FileNode> nodes = fileQueryPort.getActiveFileNodesByIds(request.getFileIds());
        if (nodes.size() != new LinkedHashSet<>(request.getFileIds()).size()) {
            // 请求中存在已删除/不存在的文件，杜绝静默吞掉（与 requireActiveFiles 语义对齐）
            Set<Long> found = new LinkedHashSet<>();
            for (FileNode node : nodes) {
                found.add(node.getId());
            }
            for (Long fileId : request.getFileIds()) {
                if (!found.contains(fileId)) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, "所选文件中包含已删除或不存在的数据: " + fileId);
                }
            }
        }
        fileAccessGuard.requireReadAccess(nodes, request.getUserId());
        return Result.success();
    }

    @Operation(summary = "获取分享文件下载链接（窄端点，直接返回下载链接字符串）")
    @GetMapping("/{fileId}/share-download-url")
    public Result<String> getShareDownloadUrl(@PathVariable Long fileId) {
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


    /**
     * 将 FileInfoDTO 转换为 ShareFileProjectionVO（children 投影；uploadUserId 在 DTO 上不可得时为 null）。
     */
    private ShareFileProjectionVO toShareProjectionVO(FileInfoDTO dto) {
        ShareFileProjectionVO vo = new ShareFileProjectionVO();
        vo.setId(dto.getId());
        vo.setFileType(dto.getFileType());
        vo.setUuidName(dto.getUuidName());
        vo.setOriginalName(dto.getOriginalName());
        vo.setCategory(dto.getCategory());
        vo.setFileSize(dto.getFileSize());
        vo.setStorePath(dto.getStorePath());
        vo.setDeleted(dto.getDeleted());
        vo.setModifyTime(dto.getModifyTime());
        vo.setUploadUserId(null); // FileInfoDTO 不含 uploadUserId；归属校验走 share-access-check 端点
        vo.setTeamId(dto.getTeamId());
        return vo;
    }

    /**
     * 将 FileNode 转换为 ShareFileProjectionVO（含归属字段，用于 share 投影/share-access-check 的 JSON 输出）。
     */
    private ShareFileProjectionVO toShareProjectionVO(FileNode node) {
        ShareFileProjectionVO vo = new ShareFileProjectionVO();
        vo.setId(node.getId());
        vo.setFileType(node.getFileType());
        vo.setUuidName(node instanceof FileItem item ? item.getUuidName() : null);
        vo.setOriginalName(node.getOriginalName());
        vo.setCategory(node instanceof FileItem item ? item.getCategory() : null);
        vo.setFileSize(node instanceof FileItem item ? item.getFileSize() : null);
        vo.setStorePath(node.getStorePath());
        vo.setDeleted(node.getDeleted());
        vo.setModifyTime(node.getModifyTime());
        vo.setUploadUserId(node.getUploadUserId());
        vo.setTeamId(node.getTeamId());
        return vo;
    }
}
