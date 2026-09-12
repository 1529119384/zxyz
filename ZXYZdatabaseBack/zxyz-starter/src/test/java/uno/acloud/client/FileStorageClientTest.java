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
import uno.acloud.dto.PersonalStorageUsage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link FileStorageClient} 的两个"批量"接口都带**空参短路**与**异常兜底**，
 * 这两条分支直接决定上游页面会不会因为下游抖动而整页失败，因此必须逐条钉死。
 */
class FileStorageClientTest {

    private static final String BASE_URL = "http://zxyz-file-service";
    private static final String SUM_PATH = "/api/internal/storage/sum-active";
    private static final String PERSONAL_PATH = "/api/internal/storage/personal-usage-list";
    private static final String TEAM_PATH = "/api/internal/storage/team-usage-list";

    private ObjectMapper objectMapper;
    private MockRestServiceServer server;
    private FileStorageClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().messageConverters(this::installConverters);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new FileStorageClient(builder.build(), BASE_URL, "test-token", objectMapper);
    }

    /** 显式装转换器，避免依赖 RestClient 的默认消息转换器集合（跨版本会漂）。 */
    private void installConverters(List<HttpMessageConverter<?>> converters) {
        converters.clear();
        converters.add(new StringHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
    }

    // ==================== sumActiveFileSize ====================

    @Test
    void sumActiveFileSize_whenAllArgsNull_sendsZeroAndReturnsDataValue() {
        server.expect(requestTo(BASE_URL + SUM_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "test-token"))
                .andExpect(headerDoesNotExist("X-Internal-Caller-Service"))
                .andExpect(content().string(containsString("\"userId\":0")))
                .andExpect(content().string(containsString("\"teamId\":0")))
                .andExpect(content().string(containsString("\"spaceType\":0")))
                .andExpect(content().string(containsString("\"projectId\":0")))
                .andRespond(withSuccess("{\"code\":1,\"data\":1024}", MediaType.APPLICATION_JSON));

        // null 全部归一为 0：下游按"0 表示不限"做条件，传 null 会变成 "null" 字面量
        assertThat(client.sumActiveFileSize(null, null, null, null)).isEqualTo(1024L);
        server.verify();
    }

    @Test
    void sumActiveFileSize_whenArgsGiven_sendsThemVerbatim() {
        server.expect(requestTo(BASE_URL + SUM_PATH))
                .andExpect(content().string(containsString("\"userId\":11")))
                .andExpect(content().string(containsString("\"teamId\":22")))
                .andExpect(content().string(containsString("\"spaceType\":1")))
                .andExpect(content().string(containsString("\"projectId\":33")))
                .andRespond(withSuccess("{\"code\":1,\"data\":7}", MediaType.APPLICATION_JSON));

        assertThat(client.sumActiveFileSize(11L, 22L, 1, 33L)).isEqualTo(7L);
        server.verify();
    }

    @Test
    void sumActiveFileSize_whenResponseHasNoData_returnsZero() {
        server.expect(requestTo(BASE_URL + SUM_PATH))
                .andRespond(withSuccess("{\"code\":1}", MediaType.APPLICATION_JSON));

        assertThat(client.sumActiveFileSize(1L, 0L, 0, 0L)).isZero();
        server.verify();
    }

    // ==================== listPersonalStorageUsageByUsers ====================

    @Test
    void listPersonalStorageUsageByUsers_whenIdsNullOrEmpty_shortCircuitsWithoutHttpCall() {
        assertThat(client.listPersonalStorageUsageByUsers(null)).isEmpty();
        assertThat(client.listPersonalStorageUsageByUsers(List.of())).isEmpty();

        // 零 expectation ⇒ 一旦真发了请求，verify 会因" Unexpected request"失败
        server.verify();
    }

    @Test
    void listPersonalStorageUsageByUsers_mapsDataArray() {
        server.expect(requestTo(BASE_URL + PERSONAL_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("\"userIds\":[1,2]")))
                .andRespond(withSuccess(
                        "{\"code\":1,\"data\":[{\"userId\":1,\"usedStorage\":10},"
                                + "{\"userId\":2,\"usedStorage\":20}]}",
                        MediaType.APPLICATION_JSON));

        List<PersonalStorageUsage> usage = client.listPersonalStorageUsageByUsers(List.of(1L, 2L));

        assertThat(usage).hasSize(2);
        assertThat(usage.get(0).getUserId()).isEqualTo(1L);
        assertThat(usage.get(1).getUsedStorage()).isEqualTo(20L);
        server.verify();
    }

    // ==================== listTeamStorageUsageByTeamIds ====================

    @Test
    void listTeamStorageUsageByTeamIds_whenIdsNullOrEmpty_shortCircuitsWithoutHttpCall() {
        assertThat(client.listTeamStorageUsageByTeamIds(null)).isEmpty();
        assertThat(client.listTeamStorageUsageByTeamIds(List.of())).isEmpty();

        server.verify();
    }

    @Test
    void listTeamStorageUsageByTeamIds_mapsTeamIdToUsedStorage() {
        server.expect(requestTo(BASE_URL + TEAM_PATH))
                .andExpect(content().string(containsString("\"teamIds\":[7,8]")))
                .andRespond(withSuccess(
                        "{\"code\":1,\"data\":[{\"teamId\":7,\"usedStorage\":700},"
                                + "{\"teamId\":8,\"usedStorage\":800}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.listTeamStorageUsageByTeamIds(List.of(7L, 8L)))
                .containsEntry(7L, 700L)
                .containsEntry(8L, 800L);
        server.verify();
    }

    @Test
    void listTeamStorageUsageByTeamIds_whenServerErrors_returnsEmptyMapInsteadOfThrowing() {
        // 容量统计属展示型数据，下游 5xx 时宁可少显示也不能让整个列表接口 500
        server.expect(requestTo(BASE_URL + TEAM_PATH)).andRespond(withServerError());

        assertThat(client.listTeamStorageUsageByTeamIds(List.of(7L))).isEmpty();
        server.verify();
    }

    @Test
    void listTeamStorageUsageByTeamIds_whenDataMalformed_returnsEmptyMap() {
        server.expect(requestTo(BASE_URL + TEAM_PATH))
                .andRespond(withSuccess("{\"code\":1,\"data\":\"not-an-array\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.listTeamStorageUsageByTeamIds(List.of(7L))).isEmpty();
        server.verify();
    }
}
