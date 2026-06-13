package uno.acloud.team.infrastructure.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.team.config.ServiceProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 调用 im-service 的系统通知 API。
 */
@Slf4j
@Component
public class ImSystemNotificationClient extends AbstractServiceClient {

    public ImSystemNotificationClient(RestClient restClient,
                                      ServiceProperties serviceProperties,
                                      ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getImService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "IM 服务";
    }

    public void sendBatch(List<Long> userIds,
                          String type,
                          String title,
                          String content,
                          String businessType,
                          Long businessId,
                          Long teamId) {
        List<Long> normalizedUserIds = userIds == null
                ? List.of()
                : userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf
                ));
        if (normalizedUserIds.isEmpty()) {
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userIds", normalizedUserIds);
        body.put("type", type);
        body.put("title", title);
        body.put("content", content);
        body.put("businessType", businessType);
        body.put("businessId", businessId);
        body.put("teamId", teamId);

        try {
            restClient().post()
                    .uri(baseUrl() + "/api/im/internal/system-notifications/batch")
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("发送 IM 系统通知失败: type={}, businessType={}, businessId={}, teamId={}",
                    type, businessType, businessId, teamId, e);
        }
    }
}
