package uno.acloud.team.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.UserQueryClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.config.ServiceProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 user-service InternalUserController 的 HTTP 客户端。
 * 继承公共基类获取 listByIds / getUserById，保留团队服务特有的方法。
 *
 * <p>错误处理契约：createTeamUser 抛出异常（写入操作，失败不可忽略）；
 * getAllUserIds 等查询方法静默降级。updateDefaultTeam 不再静默降级——
 * P2-A4 改由 DefaultTeamSyncRetryTask 感知失败并重试，故失败时直接抛出。</p>
 */
@Slf4j
@Component
public class UserServiceClient extends UserQueryClient {

    public UserServiceClient(RestClient restClient,
                             ServiceProperties serviceProperties,
                             ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getUserService().normalizedBaseUrl(), serviceProperties.getInternalServiceToken(), objectMapper);
    }

    /**
     * 创建团队用户（insertTeamUser）。
     * @return 创建后的用户信息 DTO（含 id）。
     */
    public UserInfoDTO createTeamUser(String username, String password, String name, String email, String phone, Long defaultTeamId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        payload.put("name", name);
        payload.put("email", email);
        payload.put("phone", phone);
        payload.put("defaultTeamId", defaultTeamId);
        JsonNode root = postJson("/api/internal/users/create-team-user", payload);
        if (root.path("code").asInt() != ErrorCode.SUCCESS) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "创建团队用户失败: " + root.path("msg").asText());
        }
        return objectMapper().convertValue(root.path("data"), UserInfoDTO.class);
    }

    /**
     * 更新用户的 defaultTeamId。
     * <p>P2-A4：本方法不再静默降级，失败直接抛出 {@code BusinessException}（user-service 不可用/业务错误），
     * 由 {@code DefaultTeamSyncRetryTask} 捕获后推进重试。幂等写，重复调用安全。</p>
     */
    public void updateDefaultTeam(Long userId, Long teamId) {
        putJson("/api/internal/users/{id}/default-team", Map.of("teamId", teamId), userId);
    }

    /**
     * 获取所有用户 ID 列表。
     */
    public List<Long> getAllUserIds() {
        try {
            JsonNode root = getJson("/api/internal/users/all-ids");
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return List.of();
            }
            return objectMapper().convertValue(root.path("data"), new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("获取所有用户ID失败", e);
            return List.of();
        }
    }

    /**
     * 清除用户权限缓存（角色/权限变更后调用）。
     */
    public void clearPermissionCache(Long userId) {
        try {
            postJson("/api/internal/users/{id}/clear-permission-cache", java.util.Map.of(), userId);
        } catch (Exception e) {
            log.warn("清除用户权限缓存失败: userId={}", userId, e);
        }
    }

    /**
     * 删除用户（事务回滚补偿用）。
     */
    public void deleteUser(Long userId) {
        try {
            deleteJson("/api/internal/users/{id}", userId);
        } catch (Exception e) {
            log.warn("删除用户失败: userId={}", userId, e);
        }
    }

    /**
     * 获取已验证邮箱列表。
     */
    public List<String> getVerifiedEmails() {
        try {
            JsonNode root = getJson("/api/internal/users/verified-emails");
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return List.of();
            }
            return objectMapper().convertValue(root.path("data"), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("获取已验证邮箱列表失败", e);
            return List.of();
        }
    }

}
