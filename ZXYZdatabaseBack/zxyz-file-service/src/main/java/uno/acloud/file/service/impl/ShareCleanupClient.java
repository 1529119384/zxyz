package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.file.config.ServiceProperties;

import java.util.List;

@Slf4j
@Component
public class ShareCleanupClient extends AbstractServiceClient {

    public ShareCleanupClient(RestClient restClient,
                               ObjectMapper objectMapper,
                               ServiceProperties serviceProperties) {
        super(restClient, serviceProperties.getShareService().getBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "分享服务";
    }

    public void deleteShareItemsByFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty() || baseUrl() == null || baseUrl().isBlank()) {
            return;
        }
        try {
            postJson("/api/internal/shares/cleanup-by-files",
                    objectMapper().createObjectNode().putPOJO("fileIds", fileIds));
        } catch (RestClientResponseException e) {
            log.warn("调用分享服务清理分享条目失败: {}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("调用分享服务清理分享条目失败", e);
        }
    }
}
