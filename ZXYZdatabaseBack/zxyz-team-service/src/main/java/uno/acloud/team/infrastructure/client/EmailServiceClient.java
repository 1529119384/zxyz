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
                    .uri(baseUrl() + "/api/email/internal/send-batch-template")
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
                    .uri(baseUrl() + "/api/email/internal/schedule-batch")
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
