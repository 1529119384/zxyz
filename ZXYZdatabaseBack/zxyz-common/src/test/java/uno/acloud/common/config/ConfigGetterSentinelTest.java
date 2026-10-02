package uno.acloud.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ConfigGetter} 哨兵值语义测试（B-8）。
 *
 * <p>要守的不变量：<b>配置的「值」与「键不存在」必须是两件可区分的事</b>。
 * 旧实现用固定字面量 {@code "§NULL§"} 当哨兵，一旦 admin-service 里真有一条配置的值
 * 恰好等于它，{@link ConfigGetter#get(String)} 会把「键存在且值为哨兵」误判成
 * 「键不存在」并返回 null —— 调用方静默拿到 fallback，排查时完全看不出问题。
 * 改为实例级随机哨兵后这种碰撞不再可能。</p>
 */
class ConfigGetterSentinelTest {

    /** 固定响应体、不真发请求的桩。 */
    private static ConfigGetter configGetterReturning(String responseJson) {
        ClientHttpRequestFactory factory = (URI uri, HttpMethod method) -> new ClientHttpRequest() {
            private final HttpHeaders headers = new HttpHeaders();

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public java.util.Map<String, Object> getAttributes() {
                return new java.util.HashMap<>();
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }

            @Override
            public OutputStream getBody() {
                return new java.io.ByteArrayOutputStream();
            }

            @Override
            public ClientHttpResponse execute() throws IOException {
                byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
                // 必须显式声明 UTF-8：否则 StringHttpMessageConverter 在没有 charset 时
                // 退回 ISO-8859-1，非 ASCII 的哨兵字面量会被解成乱码（测试假失败）。
                MockClientHttpResponse response = new MockClientHttpResponse(body, HttpStatus.OK);
                response.getHeaders().setContentType(
                        org.springframework.http.MediaType.valueOf("application/json;charset=UTF-8"));
                return response;
            }
        };
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return new ConfigGetter(restClient, "http://admin-service", "token", new ObjectMapper());
    }

    @Test
    void get_returnsValueEvenWhenItEqualsTheHistoricalSentinelLiteral() {
        // admin-service 里真有一条值恰好是历史哨兵字面量的配置
        ConfigGetter getter = configGetterReturning("{\"code\":1,\"data\":\"§NULL§\"}");

        String value = getter.get("app.some.key");

        assertEquals("§NULL§", value,
                "键存在且值等于历史哨兵字面量时必须原样返回，不得被误判为「键不存在」");
    }

    @Test
    void get_returnsNullWhenKeyIsAbsent() {
        // data 为 null ⇒ 键不存在
        ConfigGetter getter = configGetterReturning("{\"code\":1,\"data\":null}");

        assertNull(getter.get("app.missing.key"), "键不存在时返回 null");
    }

    @Test
    void getString_fallsBackOnlyWhenKeyIsAbsent() {
        ConfigGetter present = configGetterReturning("{\"code\":1,\"data\":\"§NULL§\"}");
        ConfigGetter absent = configGetterReturning("{\"code\":1,\"data\":null}");

        assertEquals("§NULL§", present.getString("k", "fallback"));
        assertEquals("fallback", absent.getString("k", "fallback"));
    }
}
