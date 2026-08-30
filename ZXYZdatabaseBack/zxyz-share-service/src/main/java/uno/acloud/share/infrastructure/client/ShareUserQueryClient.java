package uno.acloud.share.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.ServiceResponseParser;
import uno.acloud.client.UserQueryClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareServiceProperties;
import uno.acloud.share.config.TeamServiceProperties;
import uno.acloud.vo.InternalUserInfoVO;

/**
 * 调用 user-service 的 HTTP 客户端（分享服务专用）。
 * <p>继承公共基类 {@link UserQueryClient}，使用 share 专属配置前缀。</p>
 */
@Component
public class ShareUserQueryClient extends UserQueryClient {

    public ShareUserQueryClient(RestClient restClient,
                                ShareServiceProperties shareServiceProperties,
                                TeamServiceProperties teamServiceProperties,
                                ObjectMapper objectMapper) {
        super(restClient,
              shareServiceProperties.getUserService().normalizedBaseUrl(),
              teamServiceProperties.getInternalServiceToken(), objectMapper);
    }

    @Nullable
    public InternalUserInfoVO getUserInfo(Long userId) {
        try {
            String responseBody = restClient().get()
                    .uri(baseUrl() + "/api/internal/users/{userId}/info", userId)
                    .headers(this::internalHeaders)
                    .retrieve()
                    .body(String.class);
            JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper(), responseBody, "获取用户信息失败");
            if (data.isNull() || data.isMissingNode()) {
                return null;
            }
            return new InternalUserInfoVO(data.path("id").asLong(), data.path("username").asText(""));
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw ServiceResponseParser.parseErrorResponse(objectMapper(), e, "获取用户信息失败");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取用户信息失败");
        }
    }

}
