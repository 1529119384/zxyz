package uno.acloud.admin.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;

/**
 * 邮件提供者服务客户端
 * <p>
 * 调用 zxyz-email-service 的邮件提供者管理接口。
 * </p>
 */
@Component
public class EmailProviderClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public EmailProviderClient(RestClient.Builder restClientBuilder,
                               @Value("${app.internal-service-token:}") String internalServiceToken) {
        this.restClient = restClientBuilder
                .baseUrl("http://zxyz-email-service:18082")
                .build();
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * 获取所有邮件提供者
     */
    public Object listAll() {
        return restClient.get()
                .uri("/api/admin/email-providers")
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .retrieve()
                .body(Object.class);
    }

    /**
     * 更新邮件提供者配置
     */
    public void updateConfig(String providerId, Object request) {
        restClient.patch()
                .uri("/api/admin/email-providers/{providerId}", providerId)
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 邮件提供者健康检查
     */
    public Object healthCheck(String providerId) {
        return restClient.get()
                .uri("/api/admin/email-providers/{providerId}/health", providerId)
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .retrieve()
                .body(Object.class);
    }
}
