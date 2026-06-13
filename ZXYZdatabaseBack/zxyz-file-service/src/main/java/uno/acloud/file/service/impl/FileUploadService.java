package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.common.FileNodeType;
import uno.acloud.common.FileSpaceType;
import uno.acloud.file.config.ServiceProperties;
import uno.acloud.file.dto.BatchConfirmUploadRequest;
import uno.acloud.file.dto.ConfirmUploadRequest;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.common.oss.GetSignUrl;
import uno.acloud.file.service.FileUploadPort;
import uno.acloud.common.util.FileNameUtil;
import uno.acloud.file.util.FileTypeUtil;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.common.oss.OssSignInfo;
import uno.acloud.file.vo.UploadConfirmItemResultVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class FileUploadService implements FileUploadPort {

    private static final String FILE_OBJECT_PREFIX = "files/";

    private static final Set<String> BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".scr", ".pif", ".com",
            ".js", ".vbs", ".vbe", ".ps1", ".psm1", ".msi",
            ".wsf", ".wsh", ".hta", ".cpl", ".msc", ".reg"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 文档
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
            // 图片
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico",
            // 压缩包
            "zip", "rar", "7z", "tar", "gz",
            // 其他
            "mp3", "mp4", "avi", "mov", "wav"
    );

    private static final long MAX_FILE_SIZE_BYTES = 500 * 1024 * 1024; // 500MB

    private final GetSignUrl getSignUrl;
    private final FileUploadPersistenceManager fileUploadPersistenceService;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String projectServiceBaseUrl;
    private final String internalServiceToken;

    public FileUploadService(GetSignUrl getSignUrl,
                             FileUploadPersistenceManager fileUploadPersistenceService,
                             FileDomainValidator fileDomainValidator,
                             FilePathResolver filePathResolver,
                             FileAccessGuard fileAccessGuardService,
                             RestClient restClient,
                             ObjectMapper objectMapper,
                             ServiceProperties serviceProperties) {
        this.getSignUrl = getSignUrl;
        this.fileUploadPersistenceService = fileUploadPersistenceService;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.projectServiceBaseUrl = serviceProperties.getProjectService().getBaseUrl();
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
    }

    public OssSignInfo getUploadSign(String originalName) {
        validateFileExtension(originalName);
        validateAllowedExtension(originalName);
        String normalizedName = fileDomainValidator.validateInputName(originalName);
        String uuidName = FILE_OBJECT_PREFIX + FileNameUtil.uuidName(normalizedName);
        return getSignUrl.generatePutSignInfo(uuidName, normalizedName);
    }

    private void validateFileExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int lastDot = lower.lastIndexOf('.');
        if (lastDot < 0) {
            return;
        }
        String ext = lower.substring(lastDot);
        if (BLOCKED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型");
        }
    }

    private void validateAllowedExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int lastDot = lower.lastIndexOf('.');
        if (lastDot < 0 || lastDot == lower.length() - 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型: 文件缺少扩展名");
        }
        String ext = lower.substring(lastDot + 1);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型: ." + ext);
        }
    }

    public BatchUploadConfirmResultVO confirmUpload(BatchConfirmUploadRequest request, Long userId) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传确认请求不能为空");
        }
        if (request.getFiles() == null || request.getFiles().isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "files 不能为空");
        }

        List<UploadConfirmItemResultVO> items = new ArrayList<>();
        Map<Long, Set<String>> reservedNamesByParent = new HashMap<>();
        int successCount = 0;
        checkBatchQuota(request, userId);

        for (ConfirmUploadRequest item : request.getFiles()) {
            if (item != null && item.getTeamId() == null) {
                item.setTeamId(request.getTeamId());
            }
            if (item != null && item.getSpaceType() == null) {
                item.setSpaceType(request.getSpaceType());
            }
            if (item != null && item.getProjectId() == null) {
                item.setProjectId(request.getProjectId());
            }
            UploadConfirmItemResultVO result = confirmSingleFile(item, userId, reservedNamesByParent);
            items.add(result);
            if ("success".equals(result.getStatus())) {
                successCount++;
            }
        }

        return new BatchUploadConfirmResultVO(
                items.size(),
                successCount,
                items.size() - successCount,
                items
        );
    }

    private void checkBatchQuota(BatchConfirmUploadRequest request, Long userId) {
        long totalSize = 0L;
        for (ConfirmUploadRequest item : request.getFiles()) {
            if (item == null || item.getFileSize() == null || item.getFileSize() <= 0) {
                continue;
            }
            totalSize += item.getFileSize();
        }
        checkUploadQuotaViaHttp(userId, request.getTeamId(), request.getSpaceType(), request.getProjectId(), totalSize);
    }

    private void checkUploadQuotaViaHttp(Long userId, Long teamId, Integer spaceType, Long projectId, long totalSize) {
        if (projectServiceBaseUrl == null || projectServiceBaseUrl.isBlank()) {
            log.warn("存储配额校验服务未配置(app.project-service.base-url)，跳过配额校验");
            return;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("userId", userId);
            body.put("teamId", teamId);
            body.put("spaceType", spaceType);
            body.put("projectId", projectId);
            body.put("totalSize", totalSize);
            restClient.post()
                    .uri(normalizeBaseUrl(projectServiceBaseUrl) + "/api/internal/storage/check-quota")
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 403) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足");
            }
            log.warn("调用存储配额校验失败: {}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("调用存储配额校验失败", e);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private UploadConfirmItemResultVO confirmSingleFile(ConfirmUploadRequest request,
                                                        Long userId,
                                                        Map<Long, Set<String>> reservedNamesByParent) {
        String clientOriginalName = request == null ? null : request.getOriginalName();
        Long parentId = request == null ? null : request.getParentId();
        try {
            validateConfirmUploadItem(request);
            // OSS HEAD 请求校验实际文件大小，防止客户端篡改 fileSize
            Long ossSize = getSignUrl.getObjectSize(request.getObjectKey());
            if (ossSize != null && ossSize > MAX_FILE_SIZE_BYTES) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制（最大 500MB）");
            }
            SpaceTarget target = resolveUploadTarget(request, userId);
            requireUploadAccess(target, userId);
            Set<String> reservedNames = reservedNamesByParent.computeIfAbsent(request.getParentId(), key -> new HashSet<>());
            String finalName = fileDomainValidator.resolveAvailableName(
                    request.getParentId(),
                    target,
                    FileNodeType.FILE,
                    request.getOriginalName(),
                    reservedNames,
                    target.ownerUserId(userId)
            );
            reservedNames.add(finalName);

            String fileUrl = getSignUrl.getFileUrl(request.getObjectKey());
            FileItem fileItem = saveFileInfo(request.getObjectKey(), finalName, request.getFileSize(), request.getParentId(), target, userId, fileUrl);
            log.info("确认上传成功 objectKey={}, originalName={}, finalName={}, fileUrl={}",
                    request.getObjectKey(), request.getOriginalName(), finalName, fileUrl);
            return new UploadConfirmItemResultVO(
                    request.getOriginalName(),
                    finalName,
                    request.getParentId(),
                    "success",
                    fileItem.getId(),
                    fileUrl,
                    ErrorCode.SUCCESS,
                    "success"
            );
        } catch (BusinessException e) {
            log.warn("确认上传失败 objectKey={}, originalName={}, reason={}",
                    request == null ? null : request.getObjectKey(), clientOriginalName, e.getMessage());
            return new UploadConfirmItemResultVO(
                    clientOriginalName,
                    null,
                    parentId,
                    "fail",
                    null,
                    null,
                    e.getErrorCode(),
                    e.getMessage()
            );
        }
    }

    private void validateConfirmUploadItem(ConfirmUploadRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传确认项不能为空");
        }
        if (request.getObjectKey() == null || request.getObjectKey().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "objectKey 不能为空");
        }
        if (!request.getObjectKey().startsWith(FILE_OBJECT_PREFIX)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法的 objectKey 前缀");
        }
        fileDomainValidator.validateInputName(request.getOriginalName());
        validateAllowedExtension(request.getOriginalName());
        if (request.getFileSize() == null || request.getFileSize() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "fileSize 非法");
        }
        if (request.getFileSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超过限制（最大 500MB）");
        }
        if (request.getParentId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "parentId 不能为空");
        }
    }

    private SpaceTarget resolveUploadTarget(ConfirmUploadRequest request, Long userId) {
        if (Long.valueOf(-1L).equals(request.getParentId())) {
            return SpaceTarget.fromRequest(request.getTeamId(), request.getSpaceType(), request.getProjectId());
        }
        var parentFolder = fileDomainValidator.requireFolder(request.getParentId());
        fileAccessGuardService.requireWriteAccess(parentFolder, userId);
        Long parentTeamId = parentFolder.getTeamId();
        if (request.getTeamId() != null && !request.getTeamId().equals(parentTeamId)) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传目录不属于当前空间");
        }
        if (request.getProjectId() != null && !request.getProjectId().equals(parentFolder.getProjectId())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "上传目录不属于当前项目空间");
        }
        return SpaceTarget.fromNode(parentFolder);
    }

    private void requireUploadAccess(SpaceTarget target, Long userId) {
        if (FileSpaceType.isProject(target.spaceType())) {
            if (target.projectId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId 不能为空");
            }
            fileAccessGuardService.requireProjectFileAccess(target.projectId(), userId);
            return;
        }
        fileAccessGuardService.requireTeamWritePermission(target.teamId(), userId);
    }

    private FileItem saveFileInfo(String uuidName,
                                  String originalName,
                                  Long fileSize,
                                  Long parentId,
                                  SpaceTarget target,
                                  Long userId,
                                  String fileUrl) {
        FileItem fileItem = FileItem.create();
        fileItem.setUuidName(uuidName);
        fileItem.setOriginalName(originalName);
        fileItem.setCategory(FileTypeUtil.classify(null, originalName));
        fileItem.setFileSize(fileSize);
        fileItem.setStorePath(filePathResolver.buildStorePath(parentId, originalName));
        fileItem.setFileUrl(fileUrl);
        fileItem.setUploadUserId(userId);
        fileItem.setTeamId(target.teamId());
        fileItem.setSpaceType(target.spaceType());
        fileItem.setProjectId(target.projectId());
        fileItem.setParentId(parentId);
        fileItem.setCreateTime(LocalDateTime.now());
        fileItem.setModifyTime(LocalDateTime.now());
        fileItem.setDeleted(FileDeleteStatus.NORMAL);
        return fileUploadPersistenceService.saveFileItem(fileItem);
    }
}
