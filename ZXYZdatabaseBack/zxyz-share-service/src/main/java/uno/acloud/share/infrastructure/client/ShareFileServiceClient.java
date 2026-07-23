package uno.acloud.share.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.FileStorageClient;
import uno.acloud.client.ServiceResponseParser;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.vo.FileDownloadUrlVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用 file-service 的 HTTP 客户端（分享服务专用）。
 * <p>继承公共基类 {@link FileStorageClient}，使用 share 专属配置前缀。</p>
 */
@Component
public class ShareFileServiceClient extends FileStorageClient {

    public ShareFileServiceClient(RestClient restClient,
                                  ShareServiceProperties shareServiceProperties,
                                  TeamServiceProperties teamServiceProperties,
                                  ObjectMapper objectMapper) {
        super(restClient,
              shareServiceProperties.getFileService().normalizedBaseUrl(),
              teamServiceProperties.getInternalServiceToken(), objectMapper);
    }

    public List<FileInfoDTO> getFileInfoByIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        try {
            String responseBody = restClient().post()
                    .uri(baseUrl() + "/api/internal/files/batch-info")
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .body(objectMapper().createObjectNode().putPOJO("fileIds", fileIds))
                    .retrieve()
                    .body(String.class);
            return parseFileInfoList(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "批量获取文件信息失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "批量获取文件信息失败");
        }
    }

    @Nullable
    public FileInfoDTO getFileInfoById(Long fileId) {
        try {
            String responseBody = restClient().get()
                    .uri(baseUrl() + "/api/internal/files/{fileId}/info", fileId)
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .retrieve()
                    .body(String.class);
            JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "获取文件信息失败");
            if (data.isNull() || data.isMissingNode()) {
                return null;
            }
            return objectMapper().treeToValue(data, FileInfoDTO.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取文件信息失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取文件信息失败");
        }
    }

    public List<FileInfoDTO> getShareChildren(Long parentId) {
        try {
            String responseBody = restClient().get()
                    .uri(baseUrl() + "/api/internal/files/{parentId}/share-children", parentId)
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .retrieve()
                    .body(String.class);
            return parseFileInfoList(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取分享子文件失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取分享子文件失败");
        }
    }

    public Map<Long, List<FileInfoDTO>> getShareChildrenByParentIds(List<Long> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return Map.of();
        }
        try {
            String responseBody = restClient().post()
                    .uri(baseUrl() + "/api/internal/files/batch-share-children")
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .body(objectMapper().createObjectNode().putPOJO("parentIds", parentIds))
                    .retrieve()
                    .body(String.class);
            return parseBatchShareChildrenResponse(responseBody);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "批量获取分享子文件失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "批量获取分享子文件失败");
        }
    }

    private Map<Long, List<FileInfoDTO>> parseBatchShareChildrenResponse(String responseBody) throws Exception {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "批量获取分享子文件失败");
        if (!data.isObject()) {
            return Map.of();
        }
        Map<Long, List<FileInfoDTO>> result = new java.util.HashMap<>();
        data.fields().forEachRemaining(entry -> {
            long key = Long.parseLong(entry.getKey());
            List<FileInfoDTO> list = new ArrayList<>();
            JsonNode arrayNode = entry.getValue();
            if (arrayNode.isArray()) {
                for (JsonNode item : arrayNode) {
                    try {
                        list.add(objectMapper().treeToValue(item, FileInfoDTO.class));
                    } catch (Exception e) {
                        throw new RuntimeException("解析分享子文件项失败", e);
                    }
                }
            }
            result.put(key, list);
        });
        return result;
    }

    public String getShareDownloadUrl(Long fileId) {
        try {
            String responseBody = restClient().get()
                    .uri(baseUrl() + "/api/internal/files/{fileId}/share-download-url", fileId)
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .retrieve()
                    .body(String.class);
            JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "获取分享下载链接失败");
            return objectMapper().treeToValue(data, FileDownloadUrlVO.class).getDownloadUrl();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取分享下载链接失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取分享下载链接失败");
        }
    }

    /**
     * 获取文件流式下载信息（用于分享服务流式下载场景）
     */
    public String getFileStreamInfo(Long fileId) {
        try {
            String responseBody = restClient().get()
                    .uri(baseUrl() + "/api/internal/files/{fileId}/stream-info", fileId)
                    .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken())
                    .retrieve()
                    .body(String.class);
            JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "获取文件流信息失败");
            return data.asText();
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取文件流信息失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取文件流信息失败");
        }
    }

    private List<FileInfoDTO> parseFileInfoList(String responseBody) throws Exception {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "获取文件信息失败");
        List<FileInfoDTO> result = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                result.add(objectMapper().treeToValue(item, FileInfoDTO.class));
            }
        }
        return result;
    }

}
