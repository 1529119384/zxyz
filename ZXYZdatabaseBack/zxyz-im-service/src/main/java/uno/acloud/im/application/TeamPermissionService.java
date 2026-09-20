package uno.acloud.im.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.client.AbstractServiceClient;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.config.ServiceProperties;
import uno.acloud.im.config.TeamServiceProperties;
import uno.acloud.im.infrastructure.client.MemberRequest;
import uno.acloud.im.infrastructure.client.PermissionCheckRequest;
import uno.acloud.im.infrastructure.client.RoleGrantRequest;
import uno.acloud.common.permission.TeamPermissionLocalCache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 团队权限 HTTP 客户端。
 * 通过 HTTP 调用 Team Service 的内部 API 获取权限数据，
 * 不再直接访问 zxyz_im 数据库的权限表。
 *
 * <p>07-B-2：并入 {@link AbstractServiceClient}。此前本类自己又实现了一遍
 * 「每服务独立密钥优先、回退共享 token」与「写 X-Internal-Caller-Service」的请求头逻辑，
 * 并且<b>没有传播 {@code X-Request-Id}</b> —— 团队服务侧的日志因此无法与 IM 侧的同一次请求对齐。
 * 继承基类后请求头（含链路 ID）由 {@code internalHeaders} 统一处理，HTTP 失败也归一成
 * {@code BusinessException}。</p>
 *
 * <p>重试口径按端点幂等性区分，与基类 {@code postJson} / {@code postJsonWithRetry} 的约定一致：
 * {@code /check}、{@code /list-permissions}、{@code /role-code} 是查询，允许重试；
 * {@code /initialize}、{@code /grant-role}、{@code /clear-role} 是写操作，不重试
 * （读超时时服务端可能已经执行，重试会产生重复副作用）。</p>
 */
@Slf4j
@Service
public class TeamPermissionService extends AbstractServiceClient {

    private final TeamPermissionLocalCache localCache;

    public TeamPermissionService(RestClient restClient,
                                 ObjectMapper objectMapper,
                                 TeamServiceProperties teamServiceProperties,
                                 ServiceProperties serviceProperties,
                                 TeamPermissionLocalCache localCache) {
        // baseUrl 在构造期解析：application.yml 给 app.team-service.base-url 配了非空默认值
        // （${TEAM_SERVICE_BASE_URL:http://zxyz-team-service}），normalizedBaseUrl() 不会抛
        // IllegalStateException；与同模块的 FileCardClient 保持同一种失败时机。
        super(restClient, teamServiceProperties.normalizedBaseUrl(),
                serviceProperties.getInternalServiceToken(), objectMapper);
        this.localCache = localCache;
    }

    @Override
    protected String serviceName() {
        return "团队服务";
    }

    // ==================== 权限检查 ====================

    /**
     * 检查成员是否有某团队权限。
     * <p>
     * 走 {@link TeamPermissionLocalCache} 做进程内缓存：一次业务请求里往往要问十几次权限，
     * 此前每次都是一次跨服务 HTTP。命中即返回，未命中才走远程并回填。
     * <p>
     * 注意这里<b>只缓存 {@code /check} 的布尔返回值</b>，不缓存 {@code /list-permissions}
     * 的结果集 —— 两个端点语义未必等价（前者可能额外考虑团队所有者/系统管理员），
     * 为不悄悄改变鉴权语义，保持「一次 code 一个条目」。
     * <p>
     * 失效由 team-service 在权限/角色变更时通过 Redis Pub/Sub 广播
     * （见 {@link TeamPermissionLocalCache#INVALIDATION_TOPIC}）；TTL 5 分钟仅作兜底。
     */
    public boolean hasPermission(Long teamId, Long userId, String permissionCode) {
        Boolean cached = localCache.getIfPresent(teamId, userId, permissionCode);
        if (cached != null) {
            return cached;
        }
        boolean result;
        try {
            JsonNode root = postJsonWithRetry("/api/internal/permissions/team/check",
                    new PermissionCheckRequest(teamId, userId, permissionCode));
            if (root.path("code").asInt() != ErrorCode.SUCCESS) {
                result = false;
            } else {
                result = root.path("data").asBoolean(false);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("检查团队权限失败: teamId={}, userId={}, permissionCode={}", teamId, userId, permissionCode, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "检查团队权限失败");
        }
        // 仅在成功拿到结果后回填；异常路径不回填，避免把失败结果缓存住
        localCache.put(teamId, userId, permissionCode, result);
        return result;
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
            JsonNode root = postJsonWithRetry("/api/internal/permissions/team/list-permissions",
                    new MemberRequest(teamId, userId));
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
            JsonNode root = postJsonWithRetry("/api/internal/permissions/team/role-code",
                    new MemberRequest(teamId, userId));
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
            postJson("/api/internal/permissions/team/initialize",
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
            postJson("/api/internal/permissions/team/grant-role",
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
            postJson("/api/internal/permissions/team/clear-role",
                    new MemberRequest(teamId, userId));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("清除成员角色失败: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "清除成员角色失败");
        }
    }

}
