package uno.acloud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

/**
 * 服务间调用响应解析工具。
 * <p>提取标准 Result 响应中的 data 字段，或将 HTTP 错误响应转换为 BusinessException。
 * 供各 ServiceClient 统一使用，避免重复的响应解析代码。</p>
 */
public final class ServiceResponseParser {

    private ServiceResponseParser() {}

    /**
     * 解析标准 Result 响应，返回 data 节点。
     * 非成功码时抛出 BusinessException。
     */
    public static JsonNode parseSuccessData(ObjectMapper objectMapper,
                                            String responseBody,
                                            String fallbackMessage) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        root.path("msg").asText(fallbackMessage));
            }
            return root.path("data");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, fallbackMessage);
        }
    }

    /**
     * 解析 HTTP 错误响应体为 BusinessException。
     * 适用于 RestClientResponseException 场景。
     */
    public static BusinessException parseErrorResponse(ObjectMapper objectMapper,
                                                       RestClientResponseException exception,
                                                       String fallbackMessage) {
        try {
            JsonNode root = objectMapper.readTree(exception.getResponseBodyAsString());
            int errorCode = root.path("code").asInt(ErrorCode.SYSTEM_ERROR);
            String message = root.path("msg").asText(
                    root.path("message").asText(fallbackMessage));
            return new BusinessException(errorCode, message);
        } catch (Exception ignored) {
            return new BusinessException(ErrorCode.SYSTEM_ERROR, fallbackMessage);
        }
    }
}
