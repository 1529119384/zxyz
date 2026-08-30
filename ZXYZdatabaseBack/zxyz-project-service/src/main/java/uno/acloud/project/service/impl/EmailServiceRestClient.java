package uno.acloud.project.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.common.Result;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.config.ServiceProperties;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class EmailServiceRestClient {

    private static final String EMAIL_SERVICE_UNAVAILABLE_MESSAGE = "邮件服务暂不可用，请稍后再试";

    private final RestClient restClient;
    private final String internalServiceToken;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.internal-service-key:}")
    private String selfServiceKey;
    @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}")
    private String sourceService;

    /** 独立密钥优先，否则回退构造传入的共享 token（过渡兼容） */
    private String effectiveInternalToken() {
        return (selfServiceKey != null && !selfServiceKey.isBlank()) ? selfServiceKey : internalServiceToken;
    }

    public EmailServiceRestClient(@Qualifier("emailRestClient") RestClient restClient,
                                  ServiceProperties serviceProperties,
                                  ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
        this.objectMapper = objectMapper;
    }

    public void postForVoid(String path, Object body) {
        exchange(path, () -> restClient.post()
                .uri(path)
                .header(InternalServiceHeaders.TOKEN_HEADER, effectiveInternalToken())
                .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Result.class));
    }

    public Object getForData(String path) {
        return exchange(path, () -> restClient.get()
                .uri(path)
                .header(InternalServiceHeaders.TOKEN_HEADER, effectiveInternalToken())
                .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Result.class));
    }

    public Object postForData(String path, Object body) {
        return exchange(path, () -> restClient.post()
                .uri(path)
                .header(InternalServiceHeaders.TOKEN_HEADER, effectiveInternalToken())
                .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body == null ? java.util.Map.of() : body)
                .retrieve()
                .body(Result.class));
    }

    public Object putForData(String path, Object body) {
        return exchange(path, () -> restClient.put()
                .uri(path)
                .header(InternalServiceHeaders.TOKEN_HEADER, effectiveInternalToken())
                .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Result.class));
    }

    private Object exchange(String path, ResultSupplier supplier) {
        try {
            Result<Object> result = supplier.get();
            if (result == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, EMAIL_SERVICE_UNAVAILABLE_MESSAGE);
            }
            Integer code = result.getCode();
            if (code == null || code != ErrorCode.SUCCESS) {
                throw new BusinessException(
                        code == null ? ErrorCode.SYSTEM_ERROR : code,
                        normalizeMessage(result.getMsg(), EMAIL_SERVICE_UNAVAILABLE_MESSAGE),
                        result.getData()
                );
            }
            return result.getData();
        } catch (RestClientResponseException e) {
            throw toBusinessException(path, e);
        } catch (RestClientException e) {
            log.warn("调用邮件服务失败：path={}", path, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, EMAIL_SERVICE_UNAVAILABLE_MESSAGE);
        }
    }

    private BusinessException toBusinessException(String path, RestClientResponseException exception) {
        Result<Object> result = parseErrorResult(path, exception);
        if (result == null) {
            return new BusinessException(ErrorCode.SYSTEM_ERROR, EMAIL_SERVICE_UNAVAILABLE_MESSAGE);
        }
        Integer errorCode = result.getCode();
        String message = normalizeMessage(result.getMsg(), EMAIL_SERVICE_UNAVAILABLE_MESSAGE);
        return new BusinessException(errorCode == null ? ErrorCode.SYSTEM_ERROR : errorCode, message, result.getData());
    }

    @Nullable
    private Result<Object> parseErrorResult(String path, RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (body == null || body.isBlank()) {
            log.warn("邮件服务返回空错误响应：path={}, status={}", path, exception.getStatusCode());
            return null;
        }
        try {
            return objectMapper.readValue(body, Result.class);
        } catch (JsonProcessingException e) {
            // 邮件服务错误响应应保持 Result 结构；解析失败时避免把底层 HTTP 异常裸透给前端。
            log.warn("邮件服务错误响应解析失败：path={}, status={}, body={}", path, exception.getStatusCode(), body, e);
            return null;
        }
    }

    private String normalizeMessage(String message, String fallbackMessage) {
        if (message == null || message.isBlank()) {
            return fallbackMessage;
        }
        return message.trim();
    }

    @FunctionalInterface
    private interface ResultSupplier {
        Result<Object> get();
    }
}
