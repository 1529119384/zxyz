package uno.acloud.im.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.UserQueryClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.config.UserServiceProperties;
import uno.acloud.im.domain.model.UserProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户服务 HTTP 客户端。通过 X-Internal-Service-Token 进行服务间鉴权。
 */
@Component
public class UserServiceClient extends UserQueryClient {

    public UserServiceClient(RestClient restClient, ObjectMapper objectMapper,
                             UserServiceProperties properties, ServiceProperties serviceProperties) {
        super(restClient, properties.normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    public List<UserProfile> searchUsers(String keyword) {
        try {
            JsonNode root = getJson("/api/internal/users/search?keyword={keyword}", keyword);
            return parseSearchResponse(root);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "同步用户信息失败");
        }
    }

    private List<UserProfile> parseSearchResponse(JsonNode root) {
        if (root.path("code").asInt() != ErrorCode.SUCCESS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, root.path("msg").asText("用户搜索失败"));
        }

        List<UserProfile> result = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            UserProfile profile = new UserProfile();
            profile.setUserId(item.path("id").asLong());
            profile.setUsername(item.path("username").asText(""));
            profile.setName(item.path("name").asText(null));
            profile.setAvatar(item.path("avatar").asText(null));
            result.add(profile);
        }
        return result;
    }
}
