package uno.acloud.im.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.client.ServiceResponseParser;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.FileServiceProperties;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.infrastructure.persistence.entity.FileCardArchiveEntry;
import uno.acloud.im.infrastructure.persistence.entity.FileCardContent;
import uno.acloud.im.infrastructure.persistence.entity.FileCardEntrySnapshot;
import uno.acloud.im.infrastructure.persistence.entity.FileCardResolveResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件卡片 HTTP 客户端。通过 X-Internal-Service-Token 进行服务间鉴权。
 *
 * <p>P1-1（ISSUE/54 §2.7）修复：内部链路在 file 端排除 Sa-Token（无用户会话），
 * 操作者身份必须由本端显式传递 —— 每个请求携带 {@code X-Acting-User-Id} 头，
 * file 端从该头解析 userId 做文件访问控制。头值由调用链传入
 * （发卡片 = WS 握手身份，解析卡片 = HTTP 拦截器身份），<b>不</b>取 StpUtil 上下文。</p>
 */
@Component
public class FileCardClient extends AbstractServiceClient {

    /** 内部链路操作者身份请求头，与 file 端 ActingUserIdResolver 对齐（common 不放常量，绕开 common 修改约束）。 */
    public static final String ACTING_USER_ID_HEADER = "X-Acting-User-Id";

    private static final List<DateTimeFormatter> MODIFY_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    );

    public FileCardClient(RestClient restClient, ObjectMapper objectMapper,
                          FileServiceProperties properties, ServiceProperties serviceProperties) {
        super(restClient, properties.normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "文件服务";
    }

    public FileCardContent snapshot(Long actingUserId, List<Long> fileIds) {
        try {
            String responseBody = restClient().post()
                    .uri(baseUrl() + "/api/internal/im-file-cards/snapshot")
                    .headers(headers -> {
                        internalHeaders(headers);
                        setActingUserHeader(headers, actingUserId);
                    })
                    .body(objectMapper().createObjectNode().putPOJO("fileIds", fileIds))
                    .retrieve()
                    .body(String.class);
            return parseSnapshotResponse(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取文件卡片快照失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取文件卡片快照失败");
        }
    }

    public FileCardResolveResult resolve(Long actingUserId, FileCardContent content) {
        try {
            String responseBody = restClient().post()
                    .uri(baseUrl() + "/api/internal/im-file-cards/resolve")
                    .headers(headers -> {
                        internalHeaders(headers);
                        setActingUserHeader(headers, actingUserId);
                    })
                    .body(content)
                    .retrieve()
                    .body(String.class);
            return parseResolveResponse(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "解析文件卡片资源状态失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "解析文件卡片资源状态失败");
        }
    }

    /**
     * 写入操作者身份头：file 端据此解析 userId（缺失/非法会被明确拒绝）。
     * <p>防御性兜底：调用链（WS 握手 / HTTP 拦截器）保证 userId 非空，
     * 但作为跨服务契约的发送端仍显式校验，避免把「无主」内部请求发给对端。</p>
     */
    private void setActingUserHeader(org.springframework.http.HttpHeaders headers, Long actingUserId) {
        if (actingUserId == null || actingUserId <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内部调用缺少操作者用户 ID");
        }
        headers.set(ACTING_USER_ID_HEADER, String.valueOf(actingUserId));
    }

    private FileCardContent parseSnapshotResponse(String responseBody) throws Exception {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "文件卡片快照失败");
        FileCardContent content = new FileCardContent();
        content.setShareType(data.path("shareType").asText(null));
        content.setOwnerUserId(data.path("ownerUserId").isMissingNode() ? null : data.path("ownerUserId").asLong());
        content.setParentId(data.path("parentId").isNull() ? null : data.path("parentId").asLong());
        content.setEntryCount(data.path("entryCount").asInt(0));
        content.setEntries(parseEntries(data.path("entries")));
        return content;
    }

    private FileCardResolveResult parseResolveResponse(String responseBody) throws Exception {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "文件卡片解析失败");
        FileCardResolveResult result = new FileCardResolveResult();
        result.setStatus(data.path("status").asText(null));
        result.setShareType(data.path("shareType").asText(null));
        result.setTitle(data.path("title").asText(null));
        result.setFolderParentId(data.path("folderParentId").isNull() ? null : data.path("folderParentId").asLong());
        result.setFolderPath(data.path("folderPath").asText(null));
        result.setDownloadUrl(data.path("downloadUrl").asText(null));
        result.setEntries(parseEntries(data.path("entries")));
        result.setArchiveEntries(parseArchiveEntries(data.path("archiveEntries")));
        return result;
    }

    private List<FileCardEntrySnapshot> parseEntries(JsonNode entriesNode) {
        List<FileCardEntrySnapshot> result = new ArrayList<>();
        if (entriesNode == null || !entriesNode.isArray()) {
            return result;
        }
        for (JsonNode item : entriesNode) {
            FileCardEntrySnapshot entry = new FileCardEntrySnapshot();
            entry.setFileId(item.path("fileId").asLong());
            entry.setFileType(item.path("fileType").asInt());
            entry.setOriginalName(item.path("originalName").asText(null));
            entry.setCategory(item.path("category").isNull() ? null : item.path("category").asInt());
            entry.setFileSize(item.path("fileSize").isNull() ? null : item.path("fileSize").asLong());
            entry.setParentId(item.path("parentId").isNull() ? null : item.path("parentId").asLong());
            entry.setStorePath(item.path("storePath").asText(null));
            JsonNode modifyTimeNode = item.path("modifyTime");
            entry.setModifyTime(parseModifyTime(
                    modifyTimeNode.isMissingNode() || modifyTimeNode.isNull() ? null : modifyTimeNode.asText()
            ));
            result.add(entry);
        }
        return result;
    }

    @Nullable
    static LocalDateTime parseModifyTime(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        for (DateTimeFormatter formatter : MODIFY_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(rawValue.trim(), formatter);
            } catch (DateTimeParseException ignored) {
                // 兼容 ISO 和 yyyy-MM-dd HH:mm:ss 两种时间格式
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件卡片时间格式无法解析");
    }

    private List<FileCardArchiveEntry> parseArchiveEntries(JsonNode entriesNode) {
        List<FileCardArchiveEntry> result = new ArrayList<>();
        if (entriesNode == null || !entriesNode.isArray()) {
            return result;
        }
        for (JsonNode item : entriesNode) {
            FileCardArchiveEntry entry = new FileCardArchiveEntry();
            entry.setFileName(item.path("fileName").asText(null));
            entry.setArchivePath(item.path("archivePath").asText(null));
            entry.setDownloadUrl(item.path("downloadUrl").asText(null));
            result.add(entry);
        }
        return result;
    }
}
