package uno.acloud.admin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.admin.config.AdminServiceProperties;
import uno.acloud.client.AbstractServiceClient;

/**
 * 邮件提供者服务客户端。
 * <p>调用 zxyz-email-service 的邮件提供者管理接口，
 * 继承 {@link AbstractServiceClient} 获得 @LoadBalanced、Resilience4j 保护和超时配置。</p>
 */
@Slf4j
@Component
public class EmailProviderClient extends AbstractServiceClient {

    public EmailProviderClient(RestClient restClient,
                               AdminServiceProperties props,
                               ObjectMapper objectMapper) {
        super(restClient, props.getEmailService().normalizedBaseUrl(),
                props.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "邮件服务";
    }

    public JsonNode listAll() {
        return getJson("/api/admin/email-providers");
    }

    public void updateConfig(String providerId, Object request) {
        patchJson("/api/admin/email-providers/{providerId}", request, providerId);
    }

    public JsonNode healthCheck(String providerId) {
        return getJson("/api/admin/email-providers/{providerId}/health", providerId);
    }
}
