package uno.acloud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServiceResponseParser} 是跨服务响应解析的统一入口，
 * 它的分支决定了"下游服务报错时调用方看到什么"——因此逐分支钉死。
 */
class ServiceResponseParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== parseSuccessData ====================

    @Test
    void parseSuccessData_whenCodeIsSuccess_returnsDataNode() {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper,
                "{\"code\":1,\"msg\":\"ok\",\"data\":{\"id\":7}}", "兜底");

        assertEquals(7, data.path("id").asInt());
    }

    @Test
    void parseSuccessData_whenCodeIsNotSuccess_throwsWithServerMessage() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                ServiceResponseParser.parseSuccessData(objectMapper,
                        "{\"code\":4000,\"msg\":\"参数不对\"}", "兜底"));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("参数不对", ex.getMessage());
    }

    @Test
    void parseSuccessData_whenMsgMissing_fallsBackToDefaultMessage() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                ServiceResponseParser.parseSuccessData(objectMapper,
                        "{\"code\":5000}", "兜底文案"));

        // 非成功码统一映射为 BAD_REQUEST；文案缺失时用调用方给的兜底文案
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertEquals("兜底文案", ex.getMessage());
    }

    @Test
    void parseSuccessData_whenBodyIsNotJson_throwsSystemErrorWithFallbackMessage() {
        // 网关/nginx 返回 HTML 错误页时不能把 HTML 当业务响应，必须降级为系统错误
        BusinessException ex = assertThrows(BusinessException.class, () ->
                ServiceResponseParser.parseSuccessData(objectMapper, "<html>502</html>", "兜底"));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("兜底", ex.getMessage());
    }

    @Test
    void parseSuccessData_whenDataAbsent_returnsMissingNode() {
        JsonNode data = ServiceResponseParser.parseSuccessData(objectMapper, "{\"code\":1}", "兜底");

        assertTrue(data.isMissingNode());
    }

    // ==================== parseErrorResponse ====================

    @Test
    void parseErrorResponse_whenBodyHasCodeAndMsg_usesThem() {
        BusinessException ex = ServiceResponseParser.parseErrorResponse(objectMapper,
                httpError("{\"code\":4400,\"msg\":\"团队不存在\"}"), "兜底");

        assertEquals(4400, ex.getErrorCode());
        assertEquals("团队不存在", ex.getMessage());
    }

    @Test
    void parseErrorResponse_whenOnlyMessageField_prefersMessageOverFallback() {
        BusinessException ex = ServiceResponseParser.parseErrorResponse(objectMapper,
                httpError("{\"code\":4401,\"message\":\"被拒绝\"}"), "兜底");

        assertEquals(4401, ex.getErrorCode());
        assertEquals("被拒绝", ex.getMessage());
    }

    @Test
    void parseErrorResponse_whenCodeMissing_defaultsToSystemError() {
        BusinessException ex = ServiceResponseParser.parseErrorResponse(objectMapper,
                httpError("{\"msg\":\"炸了\"}"), "兜底");

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("炸了", ex.getMessage());
    }

    @Test
    void parseErrorResponse_whenBodyIsNotJson_returnsSystemErrorWithFallback() {
        // 抓不到结构化错误体时不得把原始 HTML 当文案抛给上层
        BusinessException ex = ServiceResponseParser.parseErrorResponse(objectMapper,
                httpError("not-json"), "兜底");

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        assertEquals("兜底", ex.getMessage());
    }

    private static HttpClientErrorException httpError(String body) {
        return HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
