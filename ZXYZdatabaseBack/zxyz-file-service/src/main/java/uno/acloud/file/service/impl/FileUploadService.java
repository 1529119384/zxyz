package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.Duration;
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
@RefreshScope
public class FileUploadService implements FileUploadPort {

    private static final int MAX_NAME_RETRY_ATTEMPTS = 64;

    private static final String FILE_OBJECT_PREFIX = "files/";

    /**
     * 上传归属登记键：{@code file:upload-owner:{objectKey}} → userId（审计 12-2.1.2）。
     * 发签名时写入，确认时校验并消费，把「知道 objectKey 就能挂载」的死口子堵上。
     */
    private static final String UPLOAD_OWNER_KEY_PREFIX = "file:upload-owner:";

    /** 归属登记相对签名有效期的宽限（30 分钟），避免边界上「签名未过期、登记先过期」。 */
    private static final long UPLOAD_OWNER_TTL_MARGIN_MILLIS = 30L * 60L * 1000L;

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
            // 代码/标记（html/htm 已禁用，见 NEVER_ALLOWED_EXTENSIONS）
            "md", "json", "xml", "yaml", "yml", "css",
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

    /**
     * 无论白名单怎么配都禁止的扩展名（审计 L1）。
     *
     * <p>这些类型在 OSS 公网直链下会被浏览器「内联渲染」而不是下载，从而变成
     * 存储型 XSS / 钓鱼页（下载路径已被强制 attachment，但直链渲染不受控）。</p>
     *
     * <p>写在代码里的 deny 集合而不是只改白名单，是因为白名单来自 Nacos 热配置
     * （zxyz-dynamic.yml 的 allowed-extensions），改配置需人工 import 才生效；
     * 放在这里能保证即使配置被改回宽松值也不会重新放开。</p>
     */
    private static final Set<String> NEVER_ALLOWED_EXTENSIONS = Set.of("html", "htm", "xhtml", "shtml");

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
    private final StringRedisTemplate redisTemplate;

    /** OSS 预签名有效期（秒），用于推导上传归属登记键的 TTL。 */
    @Value("${app.oss.sign-expire-seconds:3600}")
    private long signExpireSeconds;

    public FileUploadService(StorageProviderRegistry registry,
                             FileUploadPersistenceManager fileUploadPersistenceService,
                             FileDomainValidator fileDomainValidator,
                             FilePathResolver filePathResolver,
                             FileAccessGuard fileAccessGuardService,
                             RestClient restClient,
                             ObjectMapper objectMapper,
                             ServiceProperties serviceProperties,
                             UsageLedgerMapper usageLedgerMapper,
                             StringRedisTemplate redisTemplate,
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
        this.redisTemplate = redisTemplate;
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

    public UploadInfo getUploadSign(String originalName, Long userId) {
        validateFileExtension(originalName);
        validateAllowedExtension(originalName);
        String normalizedName = fileDomainValidator.validateInputName(originalName);
        String uuidName = FILE_OBJECT_PREFIX + FileNameUtil.uuidName(normalizedName);
        UploadInfo uploadInfo = registry.getDefaultProvider().generateUploadInfo(uuidName, normalizedName);
        // 发签名即登记归属，confirm 阶段强制校验（审计 12-2.1.2）
        registerUploadOwner(uploadInfo == null ? uuidName : uploadInfo.getObjectKey(), userId, uploadInfo);
        return uploadInfo;
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
        if (provider.supportsPresignedUpload()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前存储提供者不支持直传上传");
        }

        // 审计 12-2.1.3：所有「不写字节」的校验必须前置。
        // 原实现先 receiveUpload 落盘、再解析目标空间/鉴权/查配额，失败路径既不补偿也不记账，
        // 于是任何一次越权或超额请求都会在存储里留下一个无主大对象（可被反复放大成存储耗尽）。
        ConfirmUploadRequest targetRequest = new ConfirmUploadRequest();
        targetRequest.setParentId(parentId);
        targetRequest.setTeamId(teamId);
        targetRequest.setSpaceType(spaceType);
        targetRequest.setProjectId(projectId);
        targetRequest.setFileSize(fileSize);
        SpaceTarget target = resolveUploadTarget(targetRequest, userId);
        requireUploadAccess(target, userId);
        checkUploadQuotaViaHttp(userId, teamId, spaceType, projectId, fileSize != null ? fileSize : 0L);

        // 边写边限长：客户端可以不报或谎报 fileSize，只有边读边计数才拦得住超额写入
        String limitMessage = "文件大小超过限制（最大 " + formatFileSize(maxFileSizeBytes()) + "）";
        long bytesWritten;
        try {
            bytesWritten = provider.receiveUpload(uuidName,
                    limitStream(inputStream, maxFileSizeBytes(), limitMessage), contentType,
                    buildContentDisposition(normalizedName));
        } catch (BusinessException e) {
            deleteQuietly(provider, uuidName);
            throw e;
        } catch (Exception e) {
            deleteQuietly(provider, uuidName);
            UploadSizeLimitExceededException limitExceeded =
                    findCause(e, UploadSizeLimitExceededException.class);
            if (limitExceeded != null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, limitExceeded.getMessage());
            }
            log.error("直传写入存储失败，已清理残留对象: objectKey={}", uuidName, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件上传失败，请稍后重试");
        }

        try {
            String fileUrl = provider.generateDownloadInfo(uuidName, normalizedName).getDownloadUrl();
            // 客户端未报大小时以实际写入字节数为准（原本会落成 null，档案无法记账）
            Long persistedSize = fileSize != null ? fileSize : bytesWritten;
            FileItem fileItem = saveFileInfo(uuidName, normalizedName, persistedSize, parentId, target, userId, fileUrl);
            log.info("直传上传成功 objectKey={}, originalName={}, finalName={}, bytes={}",
                    uuidName, normalizedName, fileItem.getOriginalName(), bytesWritten);
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
        } catch (RuntimeException e) {
            // 落库失败 → 补偿删除，避免留下无记账的孤儿对象
            deleteQuietly(provider, uuidName);
            throw e;
        }
    }

    /**
     * 包装输入流，累计超过 {@code maxBytes} 立即中断写入（审计 12-2.1.3）。
     * 不依赖 Content-Length：客户端可以不报或谎报长度，边读边计数才拦得住。
     */
    private InputStream limitStream(InputStream source, long maxBytes, String limitMessage) {
        return new java.io.FilterInputStream(source) {
            private long total = 0L;

            private void check(long next) {
                if (next > maxBytes) {
                    throw new UploadSizeLimitExceededException(limitMessage);
                }
            }

            @Override
            public int read() throws java.io.IOException {
                int value = super.read();
                if (value >= 0) {
                    total += 1;
                    check(total);
                }
                return value;
            }

            @Override
            public int read(byte[] buffer) throws java.io.IOException {
                int count = super.read(buffer);
                if (count > 0) {
                    total += count;
                    check(total);
                }
                return count;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                int count = super.read(buffer, offset, length);
                if (count > 0) {
                    total += count;
                    check(total);
                }
                return count;
            }
        };
    }

    /** 直传超限标记异常；写流方可能把它包进 IOException，故用 {@link #findCause} 沿链识别。 */
    private static final class UploadSizeLimitExceededException extends RuntimeException {
        private UploadSizeLimitExceededException(String message) {
            super(message);
        }
    }

    /** 沿 cause 链查找指定类型的异常；未命中返回 null。 */
    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == current) {
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    /** 补偿删除：清理写了一半/写完但未记账的对象；失败仅记日志（对账任务会兜底）。 */
    private void deleteQuietly(StorageProvider provider, String objectKey) {
        try {
            provider.deleteObject(objectKey);
        } catch (Exception cleanupError) {
            log.error("补偿删除存储对象失败（对象可能残留，等待对账清理）: objectKey={}", objectKey, cleanupError);
        }
    }

    /**
     * 登记「objectKey → userId」归属凭证（审计 12-2.1.2）。
     *
     * <p>objectKey 会随 fileUrl 对外暴露（GetSignUrl 拼直链），而确认接口原先只校验格式，
     * 唯一屏障是 UUID 的随机性；知道 objectKey 的人就能把他人对象挂进自己的空间。
     * 这里在发签名时留下归属，确认时强制校验。</p>
     *
     * <p>登记失败不阻断签名：拿不到凭证的后果是确认被拒（fail-closed），
     * 用户当场知道要重传，好过最后一步被静默挂到别人名下。</p>
     */
    private void registerUploadOwner(String objectKey, Long userId, UploadInfo uploadInfo) {
        if (objectKey == null || objectKey.isBlank() || userId == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    UPLOAD_OWNER_KEY_PREFIX + objectKey,
                    String.valueOf(userId),
                    Duration.ofMillis(resolveUploadOwnerTtlMillis(uploadInfo)));
        } catch (Exception e) {
            log.error("登记上传归属失败（该 objectKey 的确认将被拒，用户需重新上传）: objectKey={}, userId={}",
                    objectKey, userId, e);
        }
    }

    /** 归属登记 TTL：优先用签名自身的过期时刻 + 宽限，拿不到再退回配置项。 */
    private long resolveUploadOwnerTtlMillis(UploadInfo uploadInfo) {
        Long expireAt = uploadInfo == null ? null : uploadInfo.getExpireAt();
        if (expireAt != null) {
            long remaining = expireAt - System.currentTimeMillis();
            if (remaining > 0) {
                return remaining + UPLOAD_OWNER_TTL_MARGIN_MILLIS;
            }
        }
        return Math.max(signExpireSeconds, 60L) * 1000L + UPLOAD_OWNER_TTL_MARGIN_MILLIS;
    }

    /**
     * 校验 objectKey 归属。
     *
     * <p>用 GET 而不是 GETDEL：确认流程里有 OSS HEAD、唯一名重试等可重试步骤，
     * 若第一步就把凭证消费掉，一次瞬时失败就让客户端再也无法确认（必须重传整个文件）。
     * 改为「成功后才消费」，既保证凭证只用一次，又允许失败重试 —— 与仓库内 MQ 幂等键
     * 的正确范本（{@code UserDeletedEventConsumer}）同一思路。</p>
     */
    private void requireUploadOwnership(String objectKey, Long userId) {
        String key = UPLOAD_OWNER_KEY_PREFIX + objectKey;
        String owner;
        try {
            owner = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("读取上传归属失败，无法判定 objectKey 归属: objectKey={}", objectKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "上传凭证校验服务不可用，请稍后重试");
        }
        if (owner == null) {
            log.warn("拒绝确认：objectKey 无有效上传凭证（未申请签名/已确认过/已过期）objectKey={}, userId={}",
                    objectKey, userId);
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传凭证不存在或已失效，请重新上传");
        }
        if (!owner.equals(String.valueOf(userId))) {
            // 跨用户挂载他人对象：拒绝并留证，便于风控排查
            log.warn("拒绝确认：objectKey 归属不一致 objectKey={}, expectedOwner={}, actualUserId={}",
                    objectKey, owner, userId);
            throw new BusinessException(ErrorCode.NO_PERMISSION, "无权确认该上传对象");
        }
    }

    /** 确认成功后消费归属凭证；失败仅记日志（残留凭证最多让同一用户重复确认一次）。 */
    private void consumeUploadOwnership(String objectKey) {
        try {
            redisTemplate.delete(UPLOAD_OWNER_KEY_PREFIX + objectKey);
        } catch (Exception e) {
            log.warn("消费上传归属凭证失败（该 objectKey 仍可被同一用户重复确认一次）: objectKey={}", objectKey, e);
        }
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
        if (NEVER_ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型: ." + ext);
        }
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
                // 注意：responseBody 是**下游内部服务**的原始响应体，可能含类名 / SQL 片段 /
                // 内网地址 / 堆栈等实现细节，只允许写日志（见方法入口的 log.error），
                // 绝不拼进对外消息。此处曾写成 "上传参数异常：" + responseBody。
                throw new BusinessException(ErrorCode.BAD_REQUEST, "上传参数异常，请检查文件名或类型后重试");
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
            // 审计 12-2.1.2：确认前必须证明「这个 objectKey 就是本次用户申请过签名的那一个」
            requireUploadOwnership(request.getObjectKey(), userId);
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
            // 确认成功后才消费归属凭证：保证「一个凭证只能确认一次」，同时允许失败重试（审计 12-2.1.2）
            consumeUploadOwnership(request.getObjectKey());
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
            // e.getMessage() 可以安全地按项回传给前端：本分支能捕获到的 BusinessException
            // 要么来自本文件的校验文案，要么来自 storage / GetSignUrl 层 —— 经核对，那些位置
            // 抛出的文案全是硬编码中文常量（如"文件上传失败"、"不支持的文件类型"，
            // GetSignUrl 在捕获 SDK 异常后也是 log 详情 + 抛通用文案）。
            // 因此这里**不含**下游原始响应体，不需要"脱敏"；去掉它会白白丢掉每个文件的失败原因。
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
