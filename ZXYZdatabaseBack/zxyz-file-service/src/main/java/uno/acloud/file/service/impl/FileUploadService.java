package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
import uno.acloud.file.infrastructure.entity.UsageLedger;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;
import uno.acloud.common.util.FileNameUtil;
import uno.acloud.file.service.FileUploadPort;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.storage.UploadInfo;
import uno.acloud.file.util.FileTypeUtil;
import uno.acloud.file.vo.BatchUploadConfirmResultVO;
import uno.acloud.file.vo.UploadConfirmItemResultVO;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class FileUploadService implements FileUploadPort {

    private static final int MAX_NAME_RETRY_ATTEMPTS = 64;

    private static final String FILE_OBJECT_PREFIX = "files/";

    /** 允许上传的文件扩展名白名单 fallback（热配置不可用时使用） */
    private static final Set<String> FALLBACK_ALLOWED_EXTENSIONS = Set.of(
            // 文档
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf",
            // 图片
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico",
            // 压缩包
            "zip", "rar", "7z", "tar", "gz",
            // 音视频
            "mp3", "mp4", "avi", "mov", "wav",
            // 代码/标记
            "md", "json", "xml", "yaml", "yml", "html", "css",
            "ts", "vue", "java", "py", "go", "sql", "sh", "log",
            "ini", "conf", "toml",
            // 其他文档
            "odt", "ods", "odp", "key", "epub"
    );

    /** 危险文件扩展名黑名单 fallback（热配置不可用时使用） */
    private static final Set<String> FALLBACK_BLOCKED_EXTENSIONS = Set.of(
            ".exe", ".bat", ".cmd", ".scr", ".pif", ".com",
            ".js", ".vbs", ".vbe", ".ps1", ".psm1", ".msi",
            ".wsf", ".wsh", ".hta", ".cpl", ".msc", ".reg"
    );

    /** 单文件最大上传大小 fallback（500MB，热配置不可用时使用） */
    private static final long FALLBACK_MAX_FILE_SIZE_BYTES = 500L * 1024L * 1024L;

    private final StorageProviderRegistry registry;
    private final FileUploadPersistenceManager fileUploadPersistenceService;
    private final FileDomainValidator fileDomainValidator;
    private final FilePathResolver filePathResolver;
    private final FileAccessGuard fileAccessGuardService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String projectServiceBaseUrl;
    private final String internalServiceToken;
    @org.springframework.beans.factory.annotation.Value("${app.internal-service-key:}")
    private String selfServiceKey;
    @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}")
    private String sourceService;
    /** 允许上传的文件扩展名白名单（Nacos 注入的原始 JSON 数组字符串，缺省时回退到 FALLBACK_ALLOWED_EXTENSIONS） */
    private final String allowedExtensionsRaw;
    /** 危险文件扩展名黑名单（Nacos 注入的原始 JSON 数组字符串，缺省时回退到 FALLBACK_BLOCKED_EXTENSIONS） */
    private final String blockedExtensionsRaw;
    /** 单文件最大上传大小（Nacos 注入，缺省 500MB） */
    private final long maxUploadFileSizeBytes;
    private final UsageLedgerMapper usageLedgerMapper;

    public FileUploadService(StorageProviderRegistry registry,
                             FileUploadPersistenceManager fileUploadPersistenceService,
                             FileDomainValidator fileDomainValidator,
                             FilePathResolver filePathResolver,
                             FileAccessGuard fileAccessGuardService,
                             RestClient restClient,
                             ObjectMapper objectMapper,
                             ServiceProperties serviceProperties,
                             UsageLedgerMapper usageLedgerMapper,
                             @Value("${app.file.upload.allowed-extensions:}") String allowedExtensionsRaw,
                             @Value("${app.file.upload.blocked-extensions:}") String blockedExtensionsRaw,
                             @Value("${app.file.upload.max-size-bytes:524288000}") long maxFileSizeBytes) {
        this.registry = registry;
        this.fileUploadPersistenceService = fileUploadPersistenceService;
        this.fileDomainValidator = fileDomainValidator;
        this.filePathResolver = filePathResolver;
        this.fileAccessGuardService = fileAccessGuardService;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.projectServiceBaseUrl = serviceProperties.getProjectService().getBaseUrl();
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
        this.allowedExtensionsRaw = allowedExtensionsRaw;
        this.blockedExtensionsRaw = blockedExtensionsRaw;
        this.maxUploadFileSizeBytes = maxFileSizeBytes;
        this.usageLedgerMapper = usageLedgerMapper;
    }

    private Set<String> allowedExtensions() {
        return parseJsonSet(allowedExtensionsRaw, FALLBACK_ALLOWED_EXTENSIONS);
    }

    private Set<String> blockedExtensions() {
        return parseJsonSet(blockedExtensionsRaw, FALLBACK_BLOCKED_EXTENSIONS);
    }

    private long maxFileSizeBytes() {
        return this.maxUploadFileSizeBytes;
    }

    /**
     * 将 Nacos 注入的 JSON 数组字符串解析为扩展名集合，等价于 {@code ConfigGetter.getJsonSet}。
     * 属性缺失/为空时回退到 fallback；解析失败或非数组同样回退。
     */
    private Set<String> parseJsonSet(String raw, Set<String> fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                log.warn("配置值不是 JSON 数组，使用 fallback: value={}", raw);
                return fallback;
            }
            Set<String> result = new java.util.LinkedHashSet<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("配置值解析为 JSON 数组失败，使用 fallback: value={}", raw, e);
            return fallback;
        }
    }

    public UploadInfo getUploadSign(String originalName) {
        validateFileExtension(originalName);
        validateAllowedExtension(originalName);
        String normalizedName = fileDomainValidator.validateInputName(originalName);
        String uuidName = FILE_OBJECT_PREFIX + FileNameUtil.uuidName(normalizedName);
        return registry.getDefaultProvider().generateUploadInfo(uuidName, normalizedName);
    }

    public UploadInfo directUpload(String originalName, InputStream inputStream,
                                   String contentType, Long parentId, Long userId,
                                   Long teamId, Integer spaceType, Long projectId, Long fileSize) {
        validateFileExtension(originalName);
        validateAllowedExtension(originalName);
        String normalizedName = fileDomainValidator.validateInputName(originalName);
        String uuidName = FILE_OBJECT_PREFIX + FileNameUtil.uuidName(normalizedName);

        if (fileSize != null && fileSize > maxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件大小超过限制（最大 " + formatFileSize(maxFileSizeBytes()) + "）");
        }

        StorageProvider provider = registry.getDefaultProvider();
        if (!provider.supportsPresignedUpload()) {
            long bytesWritten = provider.receiveUpload(uuidName, inputStream, contentType,
                    buildContentDisposition(normalizedName));
            if (bytesWritten > maxFileSizeBytes()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "文件大小超过限制（最大 " + formatFileSize(maxFileSizeBytes()) + "）");
            }
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前存储提供者不支持直传上传");
        }

        String fileUrl = provider.generateDownloadInfo(uuidName, normalizedName).getDownloadUrl();
        ConfirmUploadRequest targetRequest = new ConfirmUploadRequest();
        targetRequest.setParentId(parentId);
        targetRequest.setTeamId(teamId);
        targetRequest.setSpaceType(spaceType);
        targetRequest.setProjectId(projectId);
        targetRequest.setFileSize(fileSize);
        SpaceTarget target = resolveUploadTarget(targetRequest, userId);
        requireUploadAccess(target, userId);
        checkUploadQuotaViaHttp(userId, teamId, spaceType, projectId, fileSize != null ? fileSize : 0L);
        FileItem fileItem = saveFileInfo(uuidName, normalizedName, fileSize, parentId, target, userId, fileUrl);
        return new UploadInfo(
                provider.providerId(),
                fileUrl,
                uuidName,
                fileUrl,
                contentType != null ? contentType : "application/octet-stream",
                buildContentDisposition(normalizedName),
                null,
                true
        );
    }

    private String buildContentDisposition(String originalName) {
        try {
            String encodedName = java.net.URLEncoder.encode(originalName, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return "attachment; filename*=utf-8''" + encodedName;
        } catch (Exception e) {
            return "attachment; filename=\"" + originalName + "\"";
        }
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
        if (blockedExtensions().contains(ext)) {
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
        if (!allowedExtensions().contains(ext)) {
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

    private String effectiveInternalToken() {
        return (selfServiceKey != null && !selfServiceKey.isBlank()) ? selfServiceKey : internalServiceToken;
    }

    private void checkBatchQuota(BatchConfirmUploadRequest request, Long userId) {
        // 先按与 confirmUpload 相同的默认填充逻辑，确保每个 item 的目标空间参数已解析完成
        for (ConfirmUploadRequest item : request.getFiles()) {
            if (item == null) {
                continue;
            }
            if (item.getTeamId() == null) {
                item.setTeamId(request.getTeamId());
            }
            if (item.getSpaceType() == null) {
                item.setSpaceType(request.getSpaceType());
            }
            if (item.getProjectId() == null) {
                item.setProjectId(request.getProjectId());
            }
        }
        // 配额按每个 item 解析后的目标空间(teamId/spaceType/projectId 组合)分组累计字节数
        Map<SpaceTarget, Long> sizeByTarget = new HashMap<>();
        for (ConfirmUploadRequest item : request.getFiles()) {
            if (item == null || item.getFileSize() == null || item.getFileSize() <= 0) {
                continue;
            }
            SpaceTarget target = SpaceTarget.fromRequest(item.getTeamId(), item.getSpaceType(), item.getProjectId());
            sizeByTarget.merge(target, item.getFileSize(), Long::sum);
        }
        // 对每个唯一空间分组逐个调用 check-quota，任何一组超额即整体拒绝
        for (Map.Entry<SpaceTarget, Long> entry : sizeByTarget.entrySet()) {
            SpaceTarget target = entry.getKey();
            checkUploadQuotaViaHttp(userId, target.teamId(), target.spaceType(), target.projectId(), entry.getValue());
        }
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
            QuotaCheckResponse response = restClient.post()
                    .uri(normalizeBaseUrl(projectServiceBaseUrl) + "/api/internal/storage/check-quota")
                    .header(InternalServiceHeaders.TOKEN_HEADER, effectiveInternalToken())
                    .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(QuotaCheckResponse.class)
                    .getBody();
            // P2-C2：把配额校验解析出的有效存储上限写入台账，供 confirm 同事务原子扣减守卫使用
            upsertLedgerLimit(userId, teamId, spaceType, projectId, response);
        } catch (RestClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            log.error("调用存储配额校验失败(status={}, body={})", statusCode, responseBody, e);

            // 403: 内部服务鉴权失败 或 存储空间不足
            if (statusCode == 403) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足");
            }
            // 409: 配额超限（FILE_STATE_INVALID 映射为 409）
            if (statusCode == 409) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "存储空间不足，请清理后重试");
            }
            // 400: 参数校验失败
            if (statusCode == 400) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "上传参数异常：" + responseBody);
            }
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务异常，请稍后重试");
        } catch (Exception e) {
            log.error("调用存储配额校验失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储配额校验服务不可用，请稍后重试");
        }
    }

    /** 内部 check-quota 响应体（Result<T> 的投影，data 为有效存储上限字节，NULL=不限制）。 */
    private record QuotaCheckResponse(Long data) {
    }

    /** 把内部校验返回的有效存储上限埋入配额台账对应作用域。 */
    private void upsertLedgerLimit(Long userId, Long teamId, Integer spaceType, Long projectId, QuotaCheckResponse response) {
        try {
            String scopeKey = UsageLedger.scopeKeyOf(spaceType, teamId, projectId, userId);
            Long limit = response == null ? null : response.data();
            usageLedgerMapper.ensureScopeAndLimit(scopeKey, limit);
        } catch (Exception ex) {
            // 台账写入失败不回阻断上传（原子扣减会以"行缺失=不限制"兜底，对账任务校正）
            log.warn("写入配额台账上限失败，本次按不限制处理: scopeKey={}", scopeKeyOfOrSkip(userId, teamId, spaceType, projectId), ex);
        }
    }

    private String scopeKeyOfOrSkip(Long userId, Long teamId, Integer spaceType, Long projectId) {
        try {
            return UsageLedger.scopeKeyOf(spaceType, teamId, projectId, userId);
        } catch (Exception e) {
            return "unknown";
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
            // 存储 HEAD 请求校验实际文件大小，防止客户端篡改 fileSize
            Long ossSize = registry.getDefaultProvider().getObjectSize(request.getObjectKey());
            if (ossSize == null) {
                // fail-closed：拿不到真实 ossSize 时拒绝确认，要求客户端重新上传
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "存储服务不可用，请重新上传");
            }
            Long reportedSize = request.getFileSize();
            if (reportedSize != null && !reportedSize.equals(ossSize)) {
                // 客户端自报 fileSize 与真实 ossSize 不一致，拒绝确认防止绕过校验
                log.warn("上传大小与存储实际不符，拒绝确认 objectKey={}, originalName={}, reportedFileSize={}, actualOssSize={}",
                        request.getObjectKey(), request.getOriginalName(), reportedSize, ossSize);
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小校验失败，请重新上传");
            }
            if (ossSize > maxFileSizeBytes()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "文件大小超过限制（最大 " + formatFileSize(maxFileSizeBytes()) + "）");
            }
            SpaceTarget target = resolveUploadTarget(request, userId);
            requireUploadAccess(target, userId);
            Set<String> reservedNames = reservedNamesByParent.computeIfAbsent(request.getParentId(), key -> new HashSet<>());
            FileItem fileItem;
            for (int attempt = 0; ; attempt++) {
                String finalName = fileDomainValidator.resolveAvailableName(
                        request.getParentId(),
                        target,
                        FileNodeType.FILE,
                        request.getOriginalName(),
                        reservedNames,
                        target.ownerUserId(userId)
                );
                reservedNames.add(finalName);
                try {
                    String fileUrl = registry.getDefaultProvider().generateDownloadInfo(request.getObjectKey(), request.getOriginalName()).getDownloadUrl();
                    fileItem = saveFileInfo(request.getObjectKey(), finalName, ossSize, request.getParentId(), target, userId, fileUrl);
                    break;
                } catch (DuplicateKeyException e) {
                    if (attempt >= MAX_NAME_RETRY_ATTEMPTS - 1) {
                        throw e;
                    }
                    // 并发下同名被先提交者占用，重试下一个序号名
                }
            }
            log.info("确认上传成功 objectKey={}, originalName={}, finalName={}, fileUrl={}",
                    request.getObjectKey(), request.getOriginalName(), fileItem.getOriginalName(), fileItem.getFileUrl());
            return new UploadConfirmItemResultVO(
                    request.getOriginalName(),
                    fileItem.getOriginalName(),
                    request.getParentId(),
                    "success",
                    fileItem.getId(),
                    fileItem.getFileUrl(),
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
        if (request.getFileSize() > maxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件大小超过限制（最大 " + formatFileSize(maxFileSizeBytes()) + "）");
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
        // 从存储读取文件头部字节用于 magic bytes 类型检测
        java.io.InputStream magicStream = null;
        byte[] firstBytes = registry.getDefaultProvider().readFirstBytes(uuidName, 28);
        if (firstBytes != null) {
            magicStream = new java.io.ByteArrayInputStream(firstBytes);
        }
        fileItem.setCategory(FileTypeUtil.classify(magicStream, originalName));
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
        fileItem.setStorageProvider(registry.getDefaultProvider().providerId());
        return fileUploadPersistenceService.saveFileItem(fileItem);
    }

    /** 将字节数格式化为可读字符串（KB/MB/GB） */
    private static String formatFileSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return (bytes / (1024L * 1024L * 1024L)) + " GB";
        }
        if (bytes >= 1024L * 1024L) {
            return (bytes / (1024L * 1024L)) + " MB";
        }
        if (bytes >= 1024L) {
            return (bytes / 1024L) + " KB";
        }
        return bytes + " B";
    }
}
