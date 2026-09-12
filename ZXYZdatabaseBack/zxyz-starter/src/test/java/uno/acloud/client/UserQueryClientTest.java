package uno.acloud.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uno.acloud.dto.UserInfoDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link UserQueryClient} 是"调用失败返回空/null"的降级型客户端——
 * 这类客户端最容易在出错时把半截数据当成功返回，因此把成功/非成功码/空 data/异常四条分支全钉死。
 */
class UserQueryClientTest {

    private static final String BASE_URL = "http://zxyz-user-service";
    private static final String BATCH_PATH = "/api/internal/users/batch";
    private static final String ONE_PATH = "/api/internal/users/5";

    private ObjectMapper objectMapper;
    private MockRestServiceServer server;
    private UserQueryClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().messageConverters(this::installConverters);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new UserQueryClient(builder.build(), BASE_URL, "test-token", objectMapper);
    }

    private void installConverters(List<HttpMessageConverter<?>> converters) {
        converters.clear();
        converters.add(new StringHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
    }

    // ==================== listByIds ====================

    @Test
    void listByIds_returnsMappedUsers() {
        server.expect(requestTo(BASE_URL + BATCH_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"code\":1,\"data\":[{\"id\":1,\"username\":\"alice\",\"name\":\"爱丽丝\"}]}",
                        MediaType.APPLICATION_JSON));

        List<UserInfoDTO> users = client.listByIds(List.of(1L));

        assertThat(users).hasSize(1);
        assertThat(users.get(0).getId()).isEqualTo(1L);
        assertThat(users.get(0).getUsername()).isEqualTo("alice");
        assertThat(users.get(0).getName()).isEqualTo("爱丽丝");
        server.verify();
    }

    @Test
    void listByIds_whenCodeNotSuccess_returnsEmptyList() {
        server.expect(requestTo(BASE_URL + BATCH_PATH))
                .andRespond(withSuccess("{\"code\":5000,\"msg\":\"boom\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.listByIds(List.of(1L))).isEmpty();
        server.verify();
    }

    @Test
    void listByIds_whenBodyHasNoCode_returnsEmptyList() {
        // path("code").asInt(0) 缺省为 0，与 SUCCESS(1) 不等 ⇒ 按失败处理，不能把 data 当结果返回
        server.expect(requestTo(BASE_URL + BATCH_PATH))
                .andRespond(withSuccess("{\"data\":[{\"id\":1}]}", MediaType.APPLICATION_JSON));

        assertThat(client.listByIds(List.of(1L))).isEmpty();
        server.verify();
    }

    @Test
    void listByIds_whenServer5xx_returnsEmptyListInsteadOfThrowing() {
        server.expect(requestTo(BASE_URL + BATCH_PATH)).andRespond(withServerError());

        assertThat(client.listByIds(List.of(1L))).isEmpty();
        server.verify();
    }

    // ==================== getUserById ====================

    @Test
    void getUserById_returnsMappedUser() {
        server.expect(requestTo(BASE_URL + ONE_PATH))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"code\":1,\"data\":{\"id\":5,\"username\":\"bob\"}}",
                        MediaType.APPLICATION_JSON));

        UserInfoDTO user = client.getUserById(5L);

        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(5L);
        assertThat(user.getUsername()).isEqualTo("bob");
        server.verify();
    }

    @Test
    void getUserById_whenDataNull_returnsNull() {
        server.expect(requestTo(BASE_URL + ONE_PATH))
                .andRespond(withSuccess("{\"code\":1,\"data\":null}", MediaType.APPLICATION_JSON));

        assertThat(client.getUserById(5L)).isNull();
        server.verify();
    }

    @Test
    void getUserById_whenCodeNotSuccess_returnsNull() {
        server.expect(requestTo(BASE_URL + ONE_PATH))
                .andRespond(withSuccess("{\"code\":4040}", MediaType.APPLICATION_JSON));

        assertThat(client.getUserById(5L)).isNull();
        server.verify();
    }

    @Test
    void getUserById_whenServerReturns4xx_returnsNull() {
        // 4xx 解析为 BusinessException，被 Retry 的 ignoreExceptions 排除 ⇒ 不重试，用例不拖时
        server.expect(requestTo(BASE_URL + ONE_PATH)).andRespond(withBadRequest());

        assertThat(client.getUserById(5L)).isNull();
        server.verify();
    }
}
