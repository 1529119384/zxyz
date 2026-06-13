package uno.acloud.project.service.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.project.service.MailClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EmailServiceMailClient implements MailClient {

    private final RestClient restClient;

    public EmailServiceMailClient(@Qualifier("emailRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void send(String recipient, String subject, String contentHtml) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", recipient);
        body.put("subject", subject);
        body.put("contentHtml", contentHtml);
        post("/api/email/internal/send", body);
    }

    @Override
    public void sendBatch(List<String> recipients, String subject, String contentHtml) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", recipients);
        body.put("subject", subject);
        body.put("contentHtml", contentHtml);
        post("/api/email/internal/send-batch", body);
    }

    @Override
    public void sendByTemplate(String recipient, String templateCode, Map<String, Object> variables) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", recipient);
        body.put("templateCode", templateCode);
        body.put("variables", variables);
        post("/api/email/internal/send-template", body);
    }

    @Override
    public void sendBatchByTemplate(List<String> recipients, String templateCode, Map<String, Object> variables,
                                    String businessType, String businessId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", recipients);
        body.put("templateCode", templateCode);
        body.put("variables", variables);
        body.put("businessType", businessType);
        body.put("businessId", businessId);
        post("/api/email/internal/send-template-batch", body);
    }

    @Override
    public void sendVerifyCode(String email, String scene, String requestIp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("scene", scene);
        body.put("requestIp", requestIp);
        post("/api/email/internal/verify-codes/send", body);
    }

    @Override
    public void checkVerifyCode(String email, String scene, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("scene", scene);
        body.put("code", code);
        post("/api/email/internal/verify-codes/check", body);
    }

    @Override
    public void scheduleBatch(List<String> recipients, String subject, String contentHtml,
                              LocalDateTime scheduledTime, String businessType, String businessId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", recipients);
        body.put("subject", subject);
        body.put("contentHtml", contentHtml);
        body.put("scheduledTime", scheduledTime);
        body.put("businessType", businessType);
        body.put("businessId", businessId);
        post("/api/email/internal/scheduled-batches", body);
    }

    private void post(String path, Map<String, Object> body) {
        restClient.post()
                .uri(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
