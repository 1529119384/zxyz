package uno.acloud.user.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.TeamServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.user.config.ServiceProperties;

import java.util.List;
import java.util.Map;

/**
 * 调用 team-service InternalPermissionController 的 HTTP 客户端。
 * 替代 main-service 本地的 PermissionService。
 *
 * <p>错误处理契约：ensureDefaultRole、assignBootstrapAdminRoleStrict 抛出异常（写入操作）；
 * 查询方法（getSystemRolesByUserId 等）静默降级，返回空集合。</p>
 */
@Slf4j
@Component
public class TeamServicePermissionClient extends TeamServiceClient {

    public TeamServicePermissionClient(RestClient restClient,
                                        ServiceProperties serviceProperties,
                                        ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(), serviceProperties.getInternalServiceToken(), objectMapper);
    }

    public List<String> getSystemRolesByUserId(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/permissions/user/system-roles/" + userId);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return List.of();
            }
            return objectMapper().convertValue(root.path("data"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("获取系统角色失败: userId={}", userId, e);
            return List.of();
        }
    }

    public List<String> getSystemPermissionsByUserId(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/permissions/user/system-permissions/" + userId);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return List.of();
            }
            return objectMapper().convertValue(root.path("data"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("获取系统权限失败: userId={}", userId, e);
            return List.of();
        }
    }

    public List<Long> listSystemAdminUserIds() {
        try {
            JsonNode root = getJson("/api/internal/permissions/user/system-admin-ids");
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return null;
            }
            return objectMapper().convertValue(root.path("data"), new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("获取系统管理员列表失败", e);
            return null;
        }
    }

    public void ensureDefaultRole(Long userId, String username) {
        postJson("/api/internal/permissions/ensure-default-role",
                Map.of("userId", userId, "username", username != null ? username : ""));
    }

    /**
     * 分配引导管理员角色，失败时抛出异常（用于首个用户注册，确保角色分配成功）。
     */
    public void assignBootstrapAdminRoleStrict(Long userId) {
        postJson("/api/internal/permissions/assign-bootstrap-admin",
                Map.of("userId", userId));
    }
}
