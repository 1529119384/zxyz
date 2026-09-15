package uno.acloud.team.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.team.config.ServiceProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调用 email-service 的邮件发送 API。
 */
@Slf4j
@Component
public class EmailServiceClient extends AbstractServiceClient {

    public EmailServiceClient(RestClient restClient,
                              ServiceProperties serviceProperties,
                              ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getEmailService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "邮件服务";
    }

    /**
     * ⚠️ 路径必须与 email-service 的 {@code EmailInternalController#sendBatchByTemplate} 一致。
     * 2026-09-15 修复前这里写的是 {@code /send-batch-template}（单词顺序颠倒），与提供方的
     * {@code /send-template-batch} 对不上 ⇒ 必然 404；而下面的 catch 把它降级成一条 warn，
     * 于是「管理员批量模板邮件」长期静默失败。现由 {@code InternalApiContractTest} 的
     * 「调用方 ↔ 提供方」对账门禁兜住（新增或改动此类路径时该门禁会红）。
     */
    public void sendBatchByTemplate(List<String> recipients, String templateCode,
                                    Map<String, Object> variables, String businessType, String businessId) {
        Map<String, Object> body = Map.of(
                "recipients", recipients,
                "templateCode", templateCode,
                "variables", variables != null ? variables : Map.of(),
                "businessType", businessType != null ? businessType : "",
                "businessId", businessId != null ? businessId : ""
        );
        try {
            restClient().post()
                    .uri(baseUrl() + "/api/email/internal/send-template-batch")
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("发送批量模板邮件失败: templateCode={}, recipients={}", templateCode, recipients.size(), e);
        }
    }

    /**
     * ⚠️ 路径必须与 email-service 的 {@code EmailInternalController#createScheduledBatch} 一致。
     * 2026-09-15 修复前这里是 {@code /schedule-batch}，提供方实际是 {@code /scheduled-batches}
     * ⇒ 必然 404 且被下面的 catch 吞成一条 warn，「管理员定时邮件」长期静默失败。
     */
    public void scheduleBatch(List<String> recipients, String subject, String contentHtml,
                              LocalDateTime scheduledTime, String businessType, String businessId) {
        Map<String, Object> body = Map.of(
                "recipients", recipients,
                "subject", subject,
                "contentHtml", contentHtml,
                "scheduledTime", scheduledTime.toString(),
                "businessType", businessType != null ? businessType : "",
                "businessId", businessId != null ? businessId : ""
        );
        try {
            restClient().post()
                    .uri(baseUrl() + "/api/email/internal/scheduled-batches")
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("调度批量邮件失败: subject={}", subject, e);
        }
    }
}
