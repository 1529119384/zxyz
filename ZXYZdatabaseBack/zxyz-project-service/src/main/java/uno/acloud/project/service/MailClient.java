package uno.acloud.project.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface MailClient {

    void send(String recipient, String subject, String contentHtml);

    void sendBatch(List<String> recipients, String subject, String contentHtml);

    void sendByTemplate(String recipient, String templateCode, Map<String, Object> variables);

    void sendBatchByTemplate(List<String> recipients, String templateCode, Map<String, Object> variables,
                             String businessType, String businessId);

    void sendVerifyCode(String email, String scene, String requestIp);

    void checkVerifyCode(String email, String scene, String code);

    void scheduleBatch(List<String> recipients, String subject, String contentHtml,
                       LocalDateTime scheduledTime, String businessType, String businessId);
}
