package uno.acloud.user.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.TeamServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 调用 team-service 的 HTTP 客户端，用于团队成员校验。
 * 替代 main-service 本地的 TeamMembershipValidator。
 */
@Slf4j
@Component
public class TeamServiceMemberClient extends TeamServiceClient {

    public TeamServiceMemberClient(RestClient restClient,
                                    ServiceProperties serviceProperties,
                                    ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(), serviceProperties.getInternalServiceToken(), objectMapper);
    }

    /**
     * 校验用户是否为团队活跃成员，不是则抛异常。
     */
    public void requireTeamMember(Long teamId, Long userId) {
        try {
            JsonNode root = getJson("/api/internal/teams/" + teamId + "/members/" + userId + "/active");
            if (root.path("code").asInt() != ErrorCode.SUCCESS || !root.path("data").asBoolean(false)) {
                throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "用户不在该团队中");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("校验团队成员失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "校验团队成员身份失败");
        }
    }

    /**
     * 查询用户所属团队 ID 列表。
     *
     * @return 团队 ID 列表；用户无团队关联时返回空列表
     */
    public List<Long> listUserTeamIds(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/teams/ids/by-user/" + userId);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                log.warn("查询用户所属团队列表失败: userId={}, msg={}", userId, root.path("msg").asText("unknown"));
                return List.of();
            }
            JsonNode data = root.path("data");
            if (data.isMissingNode() || !data.isArray()) {
                return List.of();
            }
            List<Long> ids = new ArrayList<>();
            for (JsonNode n : data) {
                ids.add(n.asLong());
            }
            return ids;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("查询用户所属团队列表异常: userId={}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "查询用户所属团队列表失败");
        }
    }
}
