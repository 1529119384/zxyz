package uno.acloud.share.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 file-service 的 HTTP 客户端（分享服务专用）。
 * <p>继承公共基类 {@link AbstractServiceClient}，使用 share 专属配置前缀。
 * 返回窄投影 {@link ShareFileProjection} 而非上游 FileInfoDTO，消除 DTO 污染。</p>
 */
@Component
public class ShareFileServiceClient extends AbstractServiceClient {

    public ShareFileServiceClient(RestClient restClient,
                                  ShareServiceProperties shareServiceProperties,
                                  TeamServiceProperties teamServiceProperties,
                                  ObjectMapper objectMapper) {
        super(restClient,
              shareServiceProperties.getFileService().normalizedBaseUrl(),
              teamServiceProperties.getInternalServiceToken(),
              objectMapper);
    }

    @Override
    protected String serviceName() {
        return "文件服务";
    }

    /**
     * 批量获取文件投影（用于分享范围校验等场景）。
     */
    public List<ShareFileProjection> getShareFileProjections(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        JsonNode root = postJson("/api/internal/files/batch-share-projection",
                objectMapper().createObjectNode().putPOJO("fileIds", fileIds));
        enforceSuccessCode(root, "批量获取文件投影失败");
        JsonNode data = root.path("data");
        List<ShareFileProjection> result = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                result.add(mapToProjection(item));
            }
        }
        return result;
    }

    /**
     * 按 ID 获取单个文件投影。
     */
    public ShareFileProjection getShareProjection(Long fileId) {
        JsonNode root = getJson("/api/internal/files/{fileId}/share-projection", fileId);
        enforceSuccessCode(root, "获取文件投影失败");
        JsonNode data = root.path("data");
        if (data.isNull() || data.isMissingNode()) {
            return null;
        }
        return mapToProjection(data);
    }

    /**
     * 获取分享根文件下的直接子文件/文件夹。
     */
    public List<ShareFileProjection> getShareChildren(Long parentId) {
        JsonNode root = getJson("/api/internal/files/{parentId}/share-children-projection", parentId);
        enforceSuccessCode(root, "获取子文件列表失败");
        JsonNode data = root.path("data");
        List<ShareFileProjection> result = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                result.add(mapToProjection(item));
            }
        }
        return result;
    }

    /**
     * 批量按 parentId 获取子文件/文件夹（key→list 结构）。
     */
    public Map<Long, List<ShareFileProjection>> getShareChildrenByParentIds(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        JsonNode root = postJson("/api/internal/files/batch-share-children-projection",
                objectMapper().createObjectNode().putPOJO("parentIds", parentIds));
        enforceSuccessCode(root, "批量获取子文件列表失败");
        JsonNode data = root.path("data");
        Map<Long, List<ShareFileProjection>> result = new LinkedHashMap<>();
        if (data.isObject()) {
            data.fields().forEachRemaining(entry -> {
                long key = Long.parseLong(entry.getKey());
                List<ShareFileProjection> list = new ArrayList<>();
                JsonNode arrayNode = entry.getValue();
                if (arrayNode.isArray()) {
                    for (JsonNode item : arrayNode) {
                        list.add(mapToProjection(item));
                    }
                }
                result.put(key, list);
            });
        }
        return result;
    }

    /**
     * 获取分享下载 URL。
     */
    public String getShareDownloadUrl(Long fileId) {
        JsonNode root = getJson("/api/internal/files/{fileId}/share-download-url", fileId);
        enforceSuccessCode(root, "获取下载链接失败");
        return root.path("data").asText(null);
    }

    /**
     * 获取文件流式下载信息（用于分享服务流式下载场景）。
     */
    public String getFileStreamInfo(Long fileId) {
        JsonNode root = getJson("/api/internal/files/{fileId}/stream-info", fileId);
        enforceSuccessCode(root, "获取流式下载信息失败");
        return root.path("data").asText();
    }

    // ==================== Mapping ====================

    /**
     * 校验上游返回的 code 字段，非 SUCCESS 时抛出 BusinessException。
     * <p>防止上游业务错误（code!=1）被静默吞掉，导致返回空 list/null 误导调用方。</p>
     */
    private void enforceSuccessCode(JsonNode root, String fallbackMessage) {
        if (root == null || root.path("code").asInt(ErrorCode.SUCCESS) != ErrorCode.SUCCESS) {
            String msg = root == null ? fallbackMessage : root.path("msg").asText(fallbackMessage);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, msg);
        }
    }

    private ShareFileProjection mapToProjection(JsonNode data) {
        ShareFileProjection p = new ShareFileProjection();
        p.setId(data.path("id").asLong());
        p.setFileType(data.has("fileType") ? data.path("fileType").asInt() : null);
        p.setUuidName(data.path("uuidName").asText(null));
        p.setOriginalName(data.path("originalName").asText(null));
        p.setCategory(data.path("category").asInt());
        p.setFileSize(data.path("fileSize").asLong());
        p.setStorePath(data.path("storePath").asText(null));
        p.setDeleted(data.has("deleted") ? data.path("deleted").asInt() : null);
        p.setModifyTime(parseLocalDateTime(data.path("modifyTime")));
        p.setUploadUserId(data.has("uploadUserId") && !data.path("uploadUserId").isNull() ? data.path("uploadUserId").asLong() : null);
        p.setTeamId(data.has("teamId") && !data.path("teamId").isNull() ? data.path("teamId").asLong() : null);
        return p;
    }

    /**
     * 创建分享前校验所选文件对指定用户均有读权限（P0-3 防 IDOR）。
     * <p>调用 file-service 内部端点 POST /api/internal/files/share-access-check，
     * 任一文件无读权限或已删除时服务端抛业务异常，此处转换为 BusinessException。</p>
     */
    public void checkShareFileAccess(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        JsonNode body = objectMapper().createObjectNode()
                .putPOJO("fileIds", fileIds)
                .put("userId", userId);
        JsonNode root = postJson("/api/internal/files/share-access-check", body);
        enforceSuccessCode(root, "分享文件访问校验失败");
    }

    private LocalDateTime parseLocalDateTime(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText(null);
        if (text == null) {
            return null;
        }
        return LocalDateTime.parse(text);
    }
}
