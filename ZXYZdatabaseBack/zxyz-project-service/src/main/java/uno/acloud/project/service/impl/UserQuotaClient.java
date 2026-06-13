package uno.acloud.project.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.project.config.ServiceProperties;
import uno.acloud.project.entity.UserQuota;

/**
 * 调用 user-service 的 HTTP 客户端，用于获取用户配额信息。
 * 替代 main-service 本地的 UserQuotaMapper。
 */
@Slf4j
@Component
public class UserQuotaClient extends AbstractServiceClient {

    public UserQuotaClient(RestClient restClient,
                           ServiceProperties serviceProperties,
                           ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getUserService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "用户服务";
    }

    /**
     * 获取用户存储配额。
     */
    @Nullable
    public UserQuota getByUserId(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/users/{id}/quota", userId);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return null;
            }
            JsonNode data = root.path("data");
            if (data.isNull() || data.isMissingNode()) {
                return null;
            }
            UserQuota quota = new UserQuota();
            quota.setUserId(userId);
            if (data.has("storageLimit") && !data.get("storageLimit").isNull()) {
                quota.setStorageLimit(data.get("storageLimit").asLong());
            }
            return quota;
        } catch (Exception e) {
            log.warn("获取用户配额失败: userId={}", userId, e);
            return null;
        }
    }
}
