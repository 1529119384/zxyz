package uno.acloud.project.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.config.ServiceProperties;
import uno.acloud.project.service.TeamFileAccessPort;
import uno.acloud.project.service.TeamMembershipValidator;
import uno.acloud.common.permission.TeamPermissionPort;

import java.util.List;
import java.util.Map;

/**
 * 调用 team-service 的 HTTP 客户端。
 * 替代 main-service 中直接使用 TeamMapper / TeamQuotaMapper / PermissionService 等。
 *
 * <p>错误处理契约：所有方法在团队服务不可用时抛出 BusinessException（SYSTEM_ERROR），
 * 调用方可以区分"无权限/无数据"和"服务异常"两种场景。</p>
 */
@Slf4j
@Component
public class TeamServiceClient extends AbstractServiceClient implements TeamPermissionPort, TeamFileAccessPort {

    public TeamServiceClient(RestClient restClient,
                             ServiceProperties serviceProperties,
                             ObjectMapper objectMapper) {
        super(restClient, serviceProperties.getTeamService().normalizedBaseUrl(),
              serviceProperties.getInternalServiceToken(), objectMapper);
    }

    @Override
    protected String serviceName() {
        return "团队服务";
    }

    // ==================== TeamPermissionPort ====================

    @Override
    public boolean hasPermission(Long userId, Long teamId, String permissionCode) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "teamId", teamId,
                "permissionCode", permissionCode
        );
        JsonNode root = postJson("/api/internal/permissions/has", body);
        int code = root.path("code").asInt();
        if (code == ErrorCode.SUCCESS) {
            return root.path("data").asBoolean(false);
        }
        if (code == TeamErrorCode.TEAM_PERMISSION_DENIED.getCode()) {
            return false;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "团队服务异常: " + root.path("msg").asText("权限校验失败"));
    }

    @Override
    public void check(long userId, long teamId, String permissionCode) {
        Map<String, Object> body = Map.of(
                "userId", userId,
                "teamId", teamId,
                "permissionCode", permissionCode
        );
        JsonNode root = postJson("/api/internal/permissions/check", body);
        int code = root.path("code").asInt();
        if (code != ErrorCode.SUCCESS) {
            throw new BusinessException(code, root.path("msg").asText("权限校验失败"));
        }
    }

    // ==================== TeamMembershipValidator ====================

    @Override
    public void requireTeamMember(Long teamId, Long userId) {
        JsonNode root = getJson("/api/internal/teams/" + teamId + "/members/" + userId + "/active");
        if (!root.path("data").asBoolean(false)) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "用户不在该团队中");
        }
    }

    // ==================== TeamFileAccessPort ====================

    @Override
    public void requireTeamViewPermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_MEMBER_VIEW);
    }

    @Override
    public void requireTeamWritePermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_UPDATE);
    }

    @Override
    public void requireTeamDeletePermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_MEMBER_REMOVE);
    }

    /**
     * 批量获取系统管理员用户 ID 列表（供 StorageQuotaService 使用）。
     */
    public List<Long> listSystemAdminUserIds() {
        try {
            JsonNode root = getJson("/api/internal/permissions/user/system-admin-ids");
            return objectMapper().convertValue(root.path("data"), new TypeReference<>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取系统管理员列表失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取系统管理员列表失败");
        }
    }

    // ==================== StorageQuotaService helpers ====================

    /**
     * 获取用户的系统角色列表（供 StorageQuotaService.isSystemAdmin 使用）。
     */
    public List<String> getSystemRolesByUserId(Long userId) {
        JsonNode root = getJson("/api/internal/permissions/user/system-roles/" + userId);
        return objectMapper().convertValue(root.path("data"), new TypeReference<>() {});
    }

    /**
     * 获取团队成员的个人存储上限（供 StorageQuotaService 使用）。
     */
    @Nullable
    public Long getMemberPersonalStorageLimit(Long teamId, Long userId) {
        try {
            JsonNode root = getJson("/api/internal/teams/" + teamId + "/members/" + userId + "/storage-limit");
            JsonNode data = root.path("data");
            return data.isNull() ? null : data.asLong();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取成员个人存储上限失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取成员个人存储上限失败");
        }
    }

    /**
     * 获取团队配额的存储上限（供 StorageQuotaService 使用）。
     */
    @Nullable
    public Long getTeamStorageLimit(Long teamId) {
        try {
            JsonNode root = getJson("/api/internal/teams/" + teamId + "/quota");
            JsonNode data = root.path("data");
            if (data.isNull() || data.isMissingNode()) {
                return null;
            }
            JsonNode storageLimit = data.path("storageLimit");
            return storageLimit.isNull() ? null : storageLimit.asLong();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取团队存储配额失败: teamId={}", teamId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取团队存储配额失败");
        }
    }

    /**
     * 获取用户所属的团队 ID 列表（供 StorageQuotaService 使用）。
     */
    public List<Long> listUserTeamIds(Long userId) {
        try {
            JsonNode root = getJson("/api/internal/teams/by-user/" + userId);
            List<Map<String, Object>> teams = objectMapper().convertValue(root.path("data"), new TypeReference<>() {});
            return teams.stream()
                    .map(t -> ((Number) t.get("id")).longValue())
                    .toList();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取用户团队列表失败: userId={}", userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取用户团队列表失败");
        }
    }

    /**
     * 获取团队成员的用户 ID 列表（供 StorageQuotaService 使用）。
     */
    public List<Long> listTeamMemberUserIds(Long teamId) {
        try {
            JsonNode root = getJson("/api/internal/teams/" + teamId + "/members");
            return objectMapper().convertValue(root.path("data"), new TypeReference<>() {});
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取团队成员列表失败: teamId={}", teamId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取团队成员列表失败");
        }
    }

    /**
     * 检查用户是否为活跃团队成员（供 ProjectCommandSupport 使用）。
     */
    public boolean isActiveMember(Long teamId, Long userId) {
        JsonNode root = getJson("/api/internal/teams/" + teamId + "/members/" + userId + "/active");
        int code = root.path("code").asInt();
        if (code == ErrorCode.SUCCESS) {
            return root.path("data").asBoolean(false);
        }
        if (code == TeamErrorCode.TEAM_PERMISSION_DENIED.getCode()) {
            return false;
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "团队服务异常: " + root.path("msg").asText("成员状态校验失败"));
    }
}
