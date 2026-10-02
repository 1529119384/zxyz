package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.config.ServiceProperties;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * F8（P3 解耦/重复）：配额校验客户端的错误映射必须区分 403（鉴权失败）与 409（配额不足）。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code FileUploadService.checkUploadQuotaViaHttp} 用裸 {@code RestClient} 自建了一套调用，
 * 其中把 <b>403 与 409 一起映射成「存储空间不足」</b>。两者的可操作主体完全不同：</p>
 * <ul>
 *   <li><b>409</b> = 配额真的不够 ⇒ <b>用户</b>清理文件即可解决；</li>
 *   <li><b>403</b> = 内部服务鉴权失败（token 未配置/不匹配、调用方不在白名单）⇒
 *       <b>部署/配置问题</b>，用户无论怎么清理都不可能成功。</li>
 * </ul>
 * <p>把 403 报成「存储空间不足」会让用户陷入「反复清理却始终失败」的死循环，
 * 而真正的原因（内部 token 配错）被这句话彻底掩盖 —— 属误导性错误信息。</p>
 *
 * <h2>另一处修复：不再有第二套实现</h2>
 * <p>现在上传路径与复制路径（{@code FileCopyService}）都走本客户端，错误映射只有一处，
 * 不会再出现「同一 HTTP 状态码在两个调用点语义不同」的口径漂移。</p>
 */
class ProjectStorageCheckClientTest {

    private MockRestServiceServer server;
    private ProjectStorageCheckClient client;

    @BeforeEach
    void setUp() {
        ServiceProperties props = new ServiceProperties();
        props.getProjectService().setBaseUrl("http://project-service:18080");
        props.setInternalServiceToken("test-token");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProjectStorageCheckClient(builder.build(), props, new ObjectMapper());
    }

    private void respondWith(HttpStatus status, String body) {
        server.expect(requestTo("http://project-service:18080/api/internal/storage/check-quota"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** 正常路径：{@code data} 为有效上限，必须原样返回（供调用方埋入配额台账）。 */
    @Test
    void checkQuota_returnsStorageLimitFromSuccessfulResponse() {
        respondWith(HttpStatus.OK, "{\"code\":1,\"msg\":\"ok\",\"data\":1048576}");

        assertEquals(1048576L, client.checkQuota(1L, null, 1, null, 500L));
    }

    /** {@code data} 为 null 表示不限制 ⇒ 返回 null（调用方据此跳过台账上限写入）。 */
    @Test
    void checkQuota_returnsNullWhenLimitIsUnlimited() {
        respondWith(HttpStatus.OK, "{\"code\":1,\"msg\":\"ok\",\"data\":null}");

        assertNull(client.checkQuota(1L, null, 1, null, 500L));
    }

    /**
     * 409 = 配额不足 ⇒ BAD_REQUEST，且文案要告诉用户「可以清理后重试」。
     * <p>这是<b>用户可自行处理</b>的分支。</p>
     */
    @Test
    void checkQuota_maps409ToUserActionableQuotaMessage() {
        respondWith(HttpStatus.CONFLICT, "{\"code\":4090,\"msg\":\"上传超过当前空间配额\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.checkQuota(1L, null, 1, null, 500L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("存储空间不足"),
                "409 应对应「空间不足」；实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("清理"),
                "应给出可操作指引（清理后重试）；实际：" + ex.getMessage());
    }

    /**
     * 403 = 内部鉴权失败 ⇒ <b>绝不能被报成「存储空间不足」</b>（F8 核心）。
     * <p>这是部署/配置故障，必须让运维能从用户反馈里看出来，而不是让用户白清理。</p>
     */
    @Test
    void checkQuota_maps403ToAuthFailureNotQuotaShortage() {
        respondWith(HttpStatus.FORBIDDEN, "{\"code\":4030,\"msg\":\"无权访问\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.checkQuota(1L, null, 1, null, 500L));

        // 关键：不得误导成「空间不足」
        assertFalse(ex.getMessage().contains("存储空间不足"),
                "403 是内部鉴权失败，不得报成「存储空间不足」——那会让用户反复清理却始终失败（F8）："
                        + ex.getMessage());
        assertTrue(ex.getMessage().contains("鉴权失败"),
                "应明确指出鉴权失败；实际：" + ex.getMessage());
        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode(),
                "403 属服务端/配置故障，应是 SYSTEM_ERROR 而非用户可纠正的业务错误");
    }

    /** 5xx ⇒ SYSTEM_ERROR（服务端可用性错误，用户重试可能成功）。 */
    @Test
    void checkQuota_maps5xxToSystemError() {
        respondWith(HttpStatus.INTERNAL_SERVER_ERROR, "{\"code\":5000,\"msg\":\"boom\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.checkQuota(1L, null, 1, null, 500L));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
    }

    /** 400 ⇒ SYSTEM_ERROR（调用方自身的请求构造问题，不是用户可纠正的业务错误）。 */
    @Test
    void checkQuota_maps400ToSystemError() {
        respondWith(HttpStatus.BAD_REQUEST, "{\"code\":4000,\"msg\":\"参数校验失败\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.checkQuota(1L, null, 1, null, 500L));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
    }

    /** 上游返回的原始响应体（可能含类名/SQL/内网地址）绝不得拼进对外消息。 */
    @Test
    void checkQuota_neverLeaksUpstreamResponseBodyIntoMessage() {
        respondWith(HttpStatus.FORBIDDEN,
                "{\"code\":4030,\"msg\":\"java.sql.SQLException at 10.0.0.5:3306\"}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> client.checkQuota(1L, null, 1, null, 500L));

        assertFalse(ex.getMessage().contains("10.0.0.5"),
                "下游内部实现细节（内网地址/SQL）只允许写日志，绝不进对外消息：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("SQLException"),
                "下游异常类名不得泄露给用户：" + ex.getMessage());
    }
}
