package uno.acloud.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 服务间 HTTP 调用客户端基类。
 * <p>封装公共逻辑：内部鉴权请求头、X-Request-Id 传播、统一异常处理。
 * 子类应添加 {@code @Component}，本类不注册为 Spring Bean。</p>
 *
 * <p>构造参数由子类从各自的 {@code ServiceProperties} 注入后传入，
 * 基类不使用 {@code @Value}，避免与子类配置来源冲突。</p>
 */
@Slf4j
public abstract class AbstractServiceClient {

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalServiceToken;
    private final ObjectMapper objectMapper;

    protected AbstractServiceClient(RestClient restClient,
                                    String baseUrl,
                                    String internalServiceToken,
                                    ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.internalServiceToken = internalServiceToken;
        this.objectMapper = objectMapper;
    }

    /**
     * 目标服务名称，用于日志和异常消息。
     * 例如 "用户服务"、"团队服务"。
     */
    protected abstract String serviceName();

    // ==================== Resilience ====================

    /**
     * 使用 Resilience4j 包装 HTTP 调用，提供重试和熔断保护。
     * <p>重试策略：最多 3 次，间隔 500ms，仅对 IO/超时异常重试；
     * 熔断器：滑动窗口 10 次调用，失败率 50% 时熔断，30s 后半开。</p>
     */
    private <T> T executeWithResilience(Supplier<T> action) {
        String name = "serviceClient-" + serviceName();
        Retry retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(IOException.class, TimeoutException.class)
                .ignoreExceptions(BusinessException.class)
                .build());
        CircuitBreaker cb = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        return retry.executeSupplier(() -> cb.executeSupplier(() -> {
            try {
                return action.get();
            } catch (BusinessException e) {
                throw e;
            } catch (RestClientResponseException e) {
                throw parseErrorResponse(e, serviceName() + "调用失败: " + e.getStatusCode());
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        serviceName() + "调用失败: " + e.getMessage());
            }
        }));
    }

    // ==================== Accessors ====================

    protected RestClient restClient() { return restClient; }
    protected String baseUrl() { return baseUrl; }
    protected String internalServiceToken() { return internalServiceToken; }
    protected ObjectMapper objectMapper() { return objectMapper; }

    // ==================== HTTP helpers ====================

    /**
     * 发送 JSON GET 请求。
     *
     * @param path 请求路径（相对于 baseUrl）
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode getJson(String path) {
        return getJson(path, new Object[0]);
    }

    /**
     * 发送 JSON GET 请求（支持 URI 模板变量）。
     *
     * @param path        请求路径模板（相对于 baseUrl），如 {@code /api/users/{id}}
     * @param uriVariables URI 模板变量值
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode getJson(String path, Object... uriVariables) {
        return executeWithResilience(() -> {
            String body = restClient.get()
                    .uri(baseUrl + path, uriVariables)
                    .headers(this::internalHeaders)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON GET 请求，404 响应时返回 null（不抛出异常）。
     * <p>适用于资源可能不存在的查询场景，避免调用方额外处理 HTTP 404。</p>
     *
     * @param path        请求路径模板（相对于 baseUrl）
     * @param uriVariables URI 模板变量值
     * @return 响应 JSON 根节点；404 时返回 null
     */
    protected JsonNode getJsonOptional(String path, Object... uriVariables) {
        return executeWithResilience(() -> {
            try {
                String body = restClient.get()
                        .uri(baseUrl + path, uriVariables)
                        .headers(this::internalHeaders)
                        .retrieve()
                        .body(String.class);
                return readJsonNode(body);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404) {
                    return null;
                }
                throw parseErrorResponse(e, serviceName() + "请求失败: " + path);
            }
        });
    }

    /**
     * 发送 JSON POST 请求。
     *
     * @param path    请求路径（相对于 baseUrl）
     * @param payload 请求体
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode postJson(String path, Object payload) {
        return executeWithResilience(() -> {
            String body = restClient.post()
                    .uri(baseUrl + path)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON POST 请求（支持 URI 模板变量）。
     *
     * @param path         请求路径模板（相对于 baseUrl）
     * @param payload      请求体
     * @param uriVariables URI 模板变量值
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode postJson(String path, Object payload, Object... uriVariables) {
        return executeWithResilience(() -> {
            String body = restClient.post()
                    .uri(baseUrl + path, uriVariables)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON PUT 请求。
     *
     * @param path    请求路径（相对于 baseUrl）
     * @param payload 请求体
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode putJson(String path, Object payload) {
        return executeWithResilience(() -> {
            String body = restClient.put()
                    .uri(baseUrl + path)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON PUT 请求（支持 URI 模板变量）。
     *
     * @param path         请求路径模板（相对于 baseUrl）
     * @param payload      请求体
     * @param uriVariables URI 模板变量值
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode putJson(String path, Object payload, Object... uriVariables) {
        return executeWithResilience(() -> {
            String body = restClient.put()
                    .uri(baseUrl + path, uriVariables)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON PATCH 请求。
     *
     * @param path    请求路径（相对于 baseUrl）
     * @param payload 请求体
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode patchJson(String path, Object payload) {
        return executeWithResilience(() -> {
            String body = restClient.patch()
                    .uri(baseUrl + path)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 JSON PATCH 请求（支持 URI 模板变量）。
     *
     * @param path         请求路径模板（相对于 baseUrl）
     * @param payload      请求体
     * @param uriVariables URI 模板变量值
     * @return 响应 JSON 根节点
     * @throws BusinessException 请求失败时抛出
     */
    protected JsonNode patchJson(String path, Object payload, Object... uriVariables) {
        return executeWithResilience(() -> {
            String body = restClient.patch()
                    .uri(baseUrl + path, uriVariables)
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return readJsonNode(body);
        });
    }

    /**
     * 发送 DELETE 请求（无响应体）。
     *
     * @param path 请求路径（相对于 baseUrl）
     * @throws BusinessException 请求失败时抛出
     */
    protected void deleteJson(String path) {
        deleteJson(path, new Object[0]);
    }

    /**
     * 发送 DELETE 请求（支持 URI 模板变量，无响应体）。
     *
     * @param path         请求路径模板（相对于 baseUrl）
     * @param uriVariables URI 模板变量值
     * @throws BusinessException 请求失败时抛出
     */
    protected void deleteJson(String path, Object... uriVariables) {
        executeWithResilience(() -> {
            restClient.delete()
                    .uri(baseUrl + path, uriVariables)
                    .headers(this::internalHeaders)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    /**
     * 解析 JSON 字符串，将受检异常转为 BusinessException。
     * 供 lambda 表达式内部调用（Supplier 不允许抛出受检异常）。
     */
    private JsonNode readJsonNode(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                    serviceName() + "响应解析失败: " + e.getMessage());
        }
    }

    // ==================== Response parsing ====================

    /**
     * 解析标准 Result 响应的 data 字段。
     * 非成功码时抛出 BusinessException。
     */
    protected JsonNode parseSuccessData(String responseBody, String fallbackMessage) {
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
    protected BusinessException parseErrorResponse(RestClientResponseException exception,
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

    // ==================== Internal helpers ====================

    /**
     * 注入内部服务调用所需的公共请求头：
     * X-Internal-Service-Token（鉴权）、X-Request-Id（链路追踪）。
     * 供子类在自定义 RestClient 调用中复用。
     */
    protected void internalHeaders(org.springframework.http.HttpHeaders headers) {
        headers.set(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken);
        String requestId = MDC.get("requestId");
        if (requestId != null && !requestId.isBlank()) {
            headers.set(InternalServiceHeaders.REQUEST_ID_HEADER, requestId);
        }
    }
}
