package uno.acloud.project.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.project.config.ServiceProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 调用 im-service 的项目协作 API。
 *
 * <p>错误处理契约：审批卡片写入（appendProjectCreateRequestMessage）必须抛异常，
 * 因为项目申请依赖该消息作为审批入口；其余方法（群聊创建、归档、结果通知）静默降级。</p>
 */
@Slf4j
@Component
public class ImCollaborationClient {

    private final RestClient restClient;
    private final String internalServiceToken;

    public ImCollaborationClient(@Qualifier("imRestClient") RestClient restClient,
                                 ServiceProperties serviceProperties) {
        this.restClient = restClient;
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
    }

    @Nullable
    public Long createProjectConversation(Long projectId, Long teamId, String projectName, Long leaderUserId) {
        try {
            Object response = restClient.post()
                    .uri("/api/im/projects/conversations")
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "projectId", projectId,
                            "teamId", teamId,
                            "name", projectName,
                            "leaderUserId", leaderUserId
                    ))
                    .retrieve()
                    .body(Object.class);
            return extractId(response);
        } catch (Exception e) {
            log.warn("创建 IM 项目群聊失败: projectId={}, teamId={}", projectId, teamId, e);
            return null;
        }
    }

    public void archiveProjectConversation(Long projectId) {
        try {
            restClient.patch()
                    .uri("/api/im/projects/{projectId}/archive", projectId)
                    .headers(this::internalHeaders)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("归档 IM 项目群聊失败: projectId={}", projectId, e);
        }
    }

    public void appendProjectCreateRequestMessage(ProjectCreateRequestMessagePayload payload) {
        if (payload == null) {
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("teamId", payload.getTeamId());
        body.put("applicationId", payload.getApplicationId());
        body.put("requesterUserId", payload.getRequesterUserId());
        body.put("requesterName", safeText(payload.getRequesterName()));
        body.put("projectName", payload.getProjectName());
        body.put("description", safeText(payload.getDescription()));
        body.put("leaderUserId", payload.getLeaderUserId());
        body.put("leaderName", safeText(payload.getLeaderName()));
        body.put("storageLimit", payload.getStorageLimit());
        try {
            restClient.post()
                    .uri("/api/im/projects/creation-applications/messages")
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 项目申请依赖团队消息审批卡片，写入失败必须反馈给前端。
            log.warn("写入项目申请团队消息失败: applicationId={}, teamId={}", payload.getApplicationId(), payload.getTeamId(), e);
            throw e;
        }
    }

    public void appendProjectCreateRequestReviewResultMessage(ProjectCreateRequestReviewResultPayload payload) {
        if (payload == null) {
            return;
        }
        try {
            restClient.post()
                    .uri("/api/im/projects/creation-applications/{applicationId}/result-messages", payload.getApplicationId())
                    .headers(this::internalHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "teamId", payload.getTeamId(),
                            "applicationId", payload.getApplicationId(),
                            "reviewerUserId", payload.getReviewerUserId(),
                            "approved", Boolean.TRUE.equals(payload.getApproved()),
                            "projectId", payload.getProjectId() == null ? 0L : payload.getProjectId(),
                            "projectName", payload.getProjectName(),
                            "reviewReason", safeText(payload.getReviewReason())
                    ))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("写入项目审批结果团队消息失败: applicationId={}, teamId={}", payload.getApplicationId(), payload.getTeamId(), e);
        }
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private Long extractId(Object response) {
        if (!(response instanceof Map<?, ?> root)) {
            return null;
        }
        Object data = root.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object id = dataMap.get("conversationId");
            if (id instanceof Number number) {
                return number.longValue();
            }
        }
        return null;
    }

    private void internalHeaders(HttpHeaders headers) {
        headers.set(InternalServiceHeaders.TOKEN_HEADER, internalServiceToken);
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
