package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.InternalServiceHeaders;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.config.TeamServiceProperties;
import uno.acloud.im.infrastructure.client.MemberRequest;
import uno.acloud.im.infrastructure.client.PermissionCheckRequest;
import uno.acloud.im.infrastructure.client.RoleGrantRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 团队权限 HTTP 客户端
 * 通过 HTTP 调用 Team Service 的内部 API 获取权限数据，
 * 不再直接访问 zxyz_im 数据库的权限表。
 */
@Slf4j
@Service
public class TeamPermissionService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TeamServiceProperties teamServiceProperties;
    private final String internalServiceToken;
    private final String selfServiceKey;
    private final String sourceService;

    public TeamPermissionService(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 TeamServiceProperties teamServiceProperties,
                                 ServiceProperties serviceProperties,
                                 @org.springframework.beans.factory.annotation.Value("${spring.application.name:unknown}") String sourceService,
                                 @org.springframework.beans.factory.annotation.Value("${app.internal-service-key:}") String selfServiceKey) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.teamServiceProperties = teamServiceProperties;
        this.internalServiceToken = serviceProperties.getInternalServiceToken();
        this.sourceService = sourceService;
        this.selfServiceKey = selfServiceKey;
    }

    // ==================== 权限检查 ====================

    /** 检查成员是否有某团队权限 */
    public boolean hasPermission(Long teamId, Long userId, String permissionCode) {
        try {
            String responseBody = postToTeamService("/api/internal/permissions/team/check",
                    new PermissionCheckRequest(teamId, userId, permissionCode));
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return false;
            }
            return root.path("data").asBoolean(false);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("检查团队权限失败: teamId={}, userId={}, permissionCode={}", teamId, userId, permissionCode, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检查团队权限失败");
        }
    }

    /** 要求成员有某权限，无权限则抛出异常 */
    public void requirePermission(Long teamId, Long userId, String permissionCode) {
        if (!hasPermission(teamId, userId, permissionCode)) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "缺少团队权限: " + permissionCode);
        }
    }

    /** 批量筛选拥有指定权限的用户 */
    public List<Long> listUsersWithPermission(Long teamId, List<Long> userIds, String permissionCode) {
        List<Long> result = new ArrayList<>();
        for (Long userId : userIds) {
            try {
                if (hasPermission(teamId, userId, permissionCode)) {
                    result.add(userId);
                }
            } catch (Exception e) {
                log.warn("批量权限检查跳过用户: teamId={}, userId={}", teamId, userId, e);
            }
        }
        return result;
    }

    /** 列出成员所有权限 code */
    public List<String> listMemberPermissions(Long teamId, Long userId) {
        try {
            String responseBody = postToTeamService("/api/internal/permissions/team/list-permissions",
                    new MemberRequest(teamId, userId));
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                result.add(item.asText());
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("列出成员权限失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "列出成员权限失败");
        }
    }

    /** 获取成员角色 code */
    public Optional<String> getMemberRoleCode(Long teamId, Long userId) {
        try {
            String responseBody = postToTeamService("/api/internal/permissions/team/role-code",
                    new MemberRequest(teamId, userId));
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                return Optional.empty();
            }
            return Optional.ofNullable(root.path("data").asText(null));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("获取成员角色失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "获取成员角色失败");
        }
    }

    // ==================== 角色管理 ====================

    /** 初始化团队内置角色 */
    public void initializeBuiltInRoles(Long teamId, Long ownerUserId) {
        try {
            postToTeamService("/api/internal/permissions/team/initialize",
                    new MemberRequest(teamId, ownerUserId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("初始化内置角色失败: teamId={}", teamId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化内置角色失败");
        }
    }

    /** 授权内置角色 */
    public void grantBuiltInRole(Long teamId, Long userId, String roleCode) {
        try {
            postToTeamService("/api/internal/permissions/team/grant-role",
                    new RoleGrantRequest(teamId, userId, roleCode));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("授权内置角色失败: teamId={}, userId={}, roleCode={}", teamId, userId, roleCode, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "授权内置角色失败");
        }
    }

    /** 清除成员角色 */
    public void clearMemberRole(Long teamId, Long userId) {
        try {
            postToTeamService("/api/internal/permissions/team/clear-role",
                    new MemberRequest(teamId, userId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("清除成员角色失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "清除成员角色失败");
        }
    }

    // ==================== HTTP 工具 ====================

    private String postToTeamService(String path, Object body) {
        String url = teamServiceProperties.normalizedBaseUrl() + path;
        String token = (selfServiceKey != null && !selfServiceKey.isBlank()) ? selfServiceKey : internalServiceToken;
        return restClient.post()
                .uri(url)
                .header(InternalServiceHeaders.TOKEN_HEADER, token)
                .header(InternalServiceHeaders.CALLER_SERVICE_HEADER, sourceService)
                .body(body)
                .retrieve()
                .body(String.class);
    }
}
