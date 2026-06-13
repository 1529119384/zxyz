package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.permission.TeamPermissionPort;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.config.ServiceProperties;

import java.util.Map;

/**
 * 调用 team-service 的权限校验 HTTP 客户端。
 *
 * <p>错误处理契约：check 抛出异常（权限校验失败 = 拒绝访问）；
 * hasPermission 静默降级，返回 false。</p>
 */
@Component
public class TeamServicePermissionClient extends AbstractServiceClient implements TeamPermissionPort {

    public TeamServicePermissionClient(
            RestClient restClient,
            ServiceProperties serviceProperties,
            ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "团队服务";
    }

    @Override
    public void check(long userId, long teamId, String permissionCode) {
        JsonNode root = postJson("/api/internal/permissions/check",
                Map.of("userId", userId, "teamId", teamId, "permissionCode", permissionCode));
        int code = root.path("code").asInt();
        if (code != ErrorCode.SUCCESS) {
            throw new BusinessException(code, root.path("msg").asText("权限校验失败"));
        }
    }

    @Override
    public boolean hasPermission(Long userId, Long teamId, String permissionCode) {
        try {
            JsonNode root = postJson("/api/internal/permissions/has",
                    Map.of("userId", userId, "teamId", teamId, "permissionCode", permissionCode));
            return root.path("data").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }
}
