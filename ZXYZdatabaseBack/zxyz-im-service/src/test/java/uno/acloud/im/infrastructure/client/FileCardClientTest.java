package uno.acloud.im.infrastructure.client;

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
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.FileServiceProperties;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.infrastructure.persistence.entity.FileCardContent;
import uno.acloud.im.infrastructure.persistence.entity.FileCardEntrySnapshot;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 文件卡片客户端契约测试（P1-1 修复的发送端钉子）。
 *
 * <p>关键回归点：内部链路在 file 端排除 Sa-Token（无用户会话），
 * 操作者身份必须随 {@code X-Acting-User-Id} 请求头显式传递 ——
 * 6 月起漏发该头导致 file 端 401、IM 文件卡片主路径断裂（ISSUE/54 §2.7 P1-1）。
 * 本类对 snapshot/resolve 两端点逐个断言该头存在且等于调用链传入的 userId。</p>
 */
class FileCardClientTest {

    private static final String BASE_URL = "http://zxyz-file-service";
    private static final String SNAPSHOT_PATH = "/api/internal/im-file-cards/snapshot";
    private static final String RESOLVE_PATH = "/api/internal/im-file-cards/resolve";

    private ObjectMapper objectMapper;
    private MockRestServiceServer server;
    private FileCardClient client;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder().messageConverters(this::installConverters);
        server = MockRestServiceServer.bindTo(builder).build();
        FileServiceProperties fileServiceProperties = new FileServiceProperties();
        fileServiceProperties.setBaseUrl(BASE_URL);
        ServiceProperties serviceProperties = new ServiceProperties();
        serviceProperties.setInternalServiceToken("test-internal-token");
        client = new FileCardClient(builder.build(), objectMapper, fileServiceProperties, serviceProperties);
    }

    /** 显式装转换器，避免依赖 RestClient 的默认消息转换器集合（跨版本会漂）。 */
    private void installConverters(List<HttpMessageConverter<?>> converters) {
        converters.clear();
        converters.add(new StringHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
    }

    // ==================== snapshot ====================

    @Test
    void snapshot_sendsActingUserIdAndInternalTokenHeaders() {
        server.expect(requestTo(BASE_URL + SNAPSHOT_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(header("X-Acting-User-Id", "42"))
                .andExpect(jsonPath("$.fileIds[0]").value(11))
                .andExpect(jsonPath("$.fileIds[1]").value(12))
                .andRespond(withSuccess("""
                        {"code":1,"data":{"shareType":"MULTI_FILE","ownerUserId":42,"parentId":-1,
                        "entryCount":2,"entries":[{"fileId":11,"fileType":1,"originalName":"a.txt",
                        "category":1,"fileSize":10,"parentId":-1,"storePath":"/p","modifyTime":"2026-04-27 13:45:12"}]}}
                        """, MediaType.APPLICATION_JSON));

        FileCardContent result = client.snapshot(42L, List.of(11L, 12L));

        assertEquals("MULTI_FILE", result.getShareType());
        assertEquals(42L, result.getOwnerUserId());
        assertEquals(2, result.getEntryCount());
        assertEquals(1, result.getEntries().size());
        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result.getEntries().get(0).getModifyTime());
        server.verify();
    }

    @Test
    void snapshot_rejectsMissingActingUserIdWithoutSendingRequest() {
        // 发送端兜底：无主内部请求不出门（file 端会明确拒绝）
        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.snapshot(null, List.of(11L)));

        assertEquals(uno.acloud.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        server.verify();
    }

    @Test
    void snapshot_rejectsNonPositiveActingUserIdWithoutSendingRequest() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.snapshot(0L, List.of(11L)));

        assertEquals(uno.acloud.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        server.verify();
    }

    // ==================== resolve ====================

    @Test
    void resolve_sendsActingUserIdHeaderAndParsesResult() {
        FileCardContent request = new FileCardContent();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(42L);
        request.setEntryCount(1);
        FileCardEntrySnapshot entry = new FileCardEntrySnapshot();
        entry.setFileId(11L);
        entry.setFileType(1);
        entry.setOriginalName("a.txt");
        entry.setParentId(-1L);
        entry.setStorePath("/p");
        request.setEntries(List.of(entry));

        server.expect(requestTo(BASE_URL + RESOLVE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Service-Token", "test-internal-token"))
                .andExpect(header("X-Acting-User-Id", "42"))
                .andExpect(content().string(containsString("\"shareType\":\"SINGLE_FILE\"")))
                .andRespond(withSuccess("""
                        {"code":1,"data":{"status":"AVAILABLE","shareType":"SINGLE_FILE","title":"a.txt",
                        "folderParentId":-1,"folderPath":null,"downloadUrl":"https://oss.example.com/a.txt",
                        "entries":[{"fileId":11,"fileType":1,"originalName":"a.txt"}],"archiveEntries":[]}}
                        """, MediaType.APPLICATION_JSON));

        var result = client.resolve(42L, request);

        assertEquals("AVAILABLE", result.getStatus());
        assertEquals("a.txt", result.getTitle());
        assertEquals("https://oss.example.com/a.txt", result.getDownloadUrl());
        assertNotNull(result.getEntries());
        assertEquals(1, result.getEntries().size());
        server.verify();
    }

    @Test
    void resolve_rejectsMissingActingUserIdWithoutSendingRequest() {
        FileCardContent request = new FileCardContent();
        request.setShareType("SINGLE_FILE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.resolve(null, request));

        assertEquals(uno.acloud.common.ErrorCode.BAD_REQUEST, ex.getErrorCode());
        server.verify();
    }

    // ==================== parseModifyTime（既有回归钉） ====================

    @Test
    void parseModifyTimeShouldSupportMainServiceDateTimePattern() {
        LocalDateTime result = FileCardClient.parseModifyTime("2026-04-27 13:45:12");

        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result);
    }

    @Test
    void parseModifyTimeShouldKeepIsoCompatibility() {
        LocalDateTime result = FileCardClient.parseModifyTime("2026-04-27T13:45:12");

        assertEquals(LocalDateTime.of(2026, 4, 27, 13, 45, 12), result);
    }

    @Test
    void parseModifyTimeShouldHandleBlankValue() {
        assertNull(FileCardClient.parseModifyTime(" "));
        assertNull(FileCardClient.parseModifyTime(null));
    }

    @Test
    void parseModifyTimeShouldRejectUnknownPattern() {
        assertThrows(BusinessException.class, () -> FileCardClient.parseModifyTime("2026/04/27 13:45:12"));
    }
}
