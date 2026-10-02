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

        // B-11（2026-10-03）：不再 catch 静默吞 —— 原实现把 HTTP 失败降级成一条 warn，
        // 「全站广播」里某批通知失败后调用方（AdminTeamService）既不知道也无从计数，
        // 广播「部分成功」完全不可观测。现把异常抛给调用方，由其在批粒度 try/catch
        // 计数（与 EmailServiceClient.sendBatchByTemplate 的批失败计数口径对齐）。
        // 配额变更等「失败不影响主流程」的调用点已由 TransactionUtils.runAfterCommit 统一吞异常，不受影响。
        restClient().post()
                .uri(baseUrl() + "/api/internal/im/system-notifications/batch")
                .headers(this::internalHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
