package uno.acloud.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uno.acloud.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link TeamServiceClient} 本身是"基础版"薄壳（没有自己的 HTTP 方法），
 * 但它的 {@code serviceName()} 会进入全部异常文案以及重试/熔断器的命名，
 * 所以用同包测试替身把它暴露出来钉死。
 */
class TeamServiceClientTest {

    private static final String BASE_URL = "http://zxyz-team-service";
    private static final String ONE_PATH = "/api/internal/teams/1";

    private MockRestServiceServer server;
    private ExposedTeamServiceClient client;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().messageConverters(converters -> {
            converters.clear();
            converters.add(new StringHttpMessageConverter());
            converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
        });
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ExposedTeamServiceClient(builder.build(), BASE_URL, "test-token", objectMapper);
    }

    @Test
    void serviceName_isTeamService() {
        assertThat(client.name()).isEqualTo("团队服务");
    }

    @Test
    void getJson_whenSuccess_returnsRootNode() {
        server.expect(requestTo(BASE_URL + ONE_PATH))
                .andRespond(withSuccess("{\"code\":1,\"data\":{\"id\":1}}", MediaType.APPLICATION_JSON));

        JsonNode root = client.get(ONE_PATH);

        assertThat(root.path("code").asInt()).isEqualTo(1);
        assertThat(root.path("data").path("id").asInt()).isEqualTo(1);
        server.verify();
    }

    @Test
    void getJson_when4xx_surfacesBusinessExceptionCarryingServiceName() {
        // 异常文案必须能看出是哪个下游服务挂了——这是线上排障的第一手线索
        server.expect(requestTo(BASE_URL + ONE_PATH)).andRespond(withBadRequest());

        BusinessException ex = assertThrows(BusinessException.class, () -> client.get(ONE_PATH));

        assertThat(ex.getMessage()).contains("团队服务");
        server.verify();
    }

    /** 同包测试替身：{@code serviceName()} 与 {@code getJson()} 均为 protected，子类可访问。 */
    private static class ExposedTeamServiceClient extends TeamServiceClient {

        ExposedTeamServiceClient(RestClient restClient, String baseUrl,
                                 String internalServiceToken, ObjectMapper objectMapper) {
            super(restClient, baseUrl, internalServiceToken, objectMapper);
        }

        String name() {
            return serviceName();
        }

        JsonNode get(String path) {
            return getJson(path);
        }
    }
}
