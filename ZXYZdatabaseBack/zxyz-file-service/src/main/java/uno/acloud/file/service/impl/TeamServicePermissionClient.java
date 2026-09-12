package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
 * hasPermission 降级返回 false 并打 warn（不再静默，审计 L4）。</p>
 */
@Slf4j
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
            // 审计 L4：这里是「静默降级为 false」——team-service 抖动时批量权限判定整体变 false，
            // 表现为用户突然「什么都没权限」，却没有任何日志或指标指向真正原因。补 warn 留痕。
            log.warn("权限校验调用失败，本次降级为无权限: userId={}, teamId={}, permissionCode={}",
                    userId, teamId, permissionCode, e);
            return false;
        }
    }
}
