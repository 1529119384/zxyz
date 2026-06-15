package uno.acloud.admin.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;

/**
 * 存储提供者服务客户端
 * <p>
 * 调用 zxyz-file-service 的存储提供者管理接口。
 * </p>
 */
@Component
public class StorageProviderClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public StorageProviderClient(RestClient.Builder restClientBuilder,
                                 @Value("${app.file-service.base-url}") String fileServiceBaseUrl,
                                 @Value("${app.internal-service-token:}") String internalServiceToken) {
        this.restClient = restClientBuilder
                .baseUrl(fileServiceBaseUrl)
                .build();
        this.internalServiceToken = internalServiceToken;
    }

    /**
     * 获取所有存储提供者
     */
    public Object listAll() {
        return restClient.get()
                .uri("/api/admin/storage-providers")
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .retrieve()
                .body(Object.class);
    }

    /**
     * 更新存储提供者配置
     */
    public void updateConfig(String providerId, Object request) {
        restClient.patch()
                .uri("/api/admin/storage-providers/{providerId}", providerId)
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * 存储提供者健康检查
     */
    public Object healthCheck(String providerId) {
        return restClient.get()
                .uri("/api/admin/storage-providers/{providerId}/health", providerId)
                .header(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken)
                .retrieve()
                .body(Object.class);
    }
}
