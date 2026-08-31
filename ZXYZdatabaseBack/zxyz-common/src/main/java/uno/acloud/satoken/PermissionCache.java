package uno.acloud.satoken;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用户权限/角色的本地缓存（Caffeine）。
 * <p>
 * 用于 P1-A2 修复：各微服务的 {@link RemoteStpInterfaceImpl} 在调用 team-service 获取
 * 用户权限/角色前先查本缓存，命中则直接返回，未命中才走远程 HTTP 并在成功后回填。
 * 当 team-service 不可用时，若缓存中存在该用户的旧值，则可基于旧值通过鉴权（降级）。
 * <p>
 * key 设计：{@code userId + ":" + loginType}。由于底层远程接口按 userId 返回权限/角色、
 * 与 loginType 无关，因此同一 userId 在不同 loginType 下的取值一致；
 * 失效时按 {@code userId + ":"} 前缀清除该用户所有 loginType 的两类缓存。
 * <p>
 * TTL 5 分钟、maximumSize 2000，与业务侧权限变更频率匹配。
 */
public class PermissionCache {

    /** Redis Pub/Sub 失效频道名（team-service 变更用户权限/角色后发布 userId） */
    public static final String INVALIDATION_TOPIC = "zxyz:permission:changed";

    private final Cache<String, List<String>> permissionCache;
    private final Cache<String, List<String>> roleCache;

    public PermissionCache() {
        this.permissionCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(2000)
                .build();
        this.roleCache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(2000)
                .build();
    }

    private static String key(Object loginId, String loginType) {
        return String.valueOf(loginId) + ":" + (loginType == null ? "" : loginType);
    }

    public List<String> getPermissions(Object loginId, String loginType) {
        return permissionCache.getIfPresent(key(loginId, loginType));
    }

    public List<String> getRoles(Object loginId, String loginType) {
        return roleCache.getIfPresent(key(loginId, loginType));
    }

    public void putPermissions(Object loginId, String loginType, List<String> permissions) {
        if (permissions != null) {
            permissionCache.put(key(loginId, loginType), permissions);
        }
    }

    public void putRoles(Object loginId, String loginType, List<String> roles) {
        if (roles != null) {
            roleCache.put(key(loginId, loginType), roles);
        }
    }

    /**
     * 清除该用户两类缓存的所有 loginType 条目。
     * 供 Redis Pub/Sub 失效链路调用（消息体仅含 userId，不含 loginType）。
     */
    public void invalidate(Object loginId) {
        String prefix = String.valueOf(loginId) + ":";
        permissionCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
        roleCache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
