package uno.acloud.common.permission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * 团队权限的「消费方本地缓存」（Caffeine）。
 * <p>
 * 背景：im / file / project 等服务判断团队权限时，都要跨服务 HTTP 问 team-service
 * （见 {@code TeamPermissionService#hasPermission}），而一次业务请求里往往要问十几次。
 * team-service 侧虽然已有 Redis 缓存（{@code TeamPermissionCacheService}），但跨服务
 * 的那一次 HTTP 往返仍然存在，且 Redis 命中也要走网络。本类在消费方进程内再挡一层。
 * <p>
 * <b>为什么只缓存布尔结果、不缓存权限列表</b>：team-service 的 {@code /check} 端点
 * 与 {@code /list-permissions} 端点<b>语义未必等价</b>（前者可能额外考虑团队所有者、
 * 系统管理员等）。为了不悄悄改变鉴权语义，这里严格缓存 {@code /check} 的返回值，
 * key 里带上 permissionCode。代价是同一 (teamId,userId) 下不同 code 各占一条，
 * 但权限 code 是有限集合，命中率依然很高。
 * <p>
 * <b>失效</b>：TTL 5 分钟兜底 + Redis Pub/Sub 主动失效。注意「只靠 TTL」在权限场景是
 * 不可接受的（改了权限要等 5 分钟才生效），所以发布方与监听方必须成对存在 ——
 * 见 {@code TeamPermissionCacheAutoConfiguration} 的注释。
 */
public class TeamPermissionLocalCache {

    /** Redis Pub/Sub 失效频道名。消息体为 {@code teamId} 或 {@code teamId:userId}。 */
    public static final String INVALIDATION_TOPIC = "zxyz:team-permission:changed";

    private final Cache<String, Boolean> cache;

    public TeamPermissionLocalCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }

    private static String key(Long teamId, Long userId, String permissionCode) {
        return teamId + ":" + userId + ":" + permissionCode;
    }

    /**
     * 读取缓存值。
     *
     * @return 缓存命中返回 TRUE/FALSE；未命中返回 {@code null}
     */
    public Boolean getIfPresent(Long teamId, Long userId, String permissionCode) {
        if (teamId == null || userId == null || permissionCode == null || permissionCode.isEmpty()) {
            return null;
        }
        return cache.getIfPresent(key(teamId, userId, permissionCode));
    }

    public void put(Long teamId, Long userId, String permissionCode, boolean allowed) {
        if (teamId == null || userId == null || permissionCode == null || permissionCode.isEmpty()) {
            return;
        }
        cache.put(key(teamId, userId, permissionCode), allowed);
    }

    /** 失效指定成员的全部权限缓存 */
    public void invalidateMember(Long teamId, Long userId) {
        removeByPrefix(teamId + ":" + userId + ":");
    }

    /** 失效整个团队的全部权限缓存（角色定义、权限分配变更） */
    public void invalidateTeam(Long teamId) {
        removeByPrefix(teamId + ":");
    }

    /** 清空（运维/测试用） */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    /** 当前条目数（测试/观测用） */
    public long size() {
        return cache.estimatedSize();
    }

    private void removeByPrefix(String prefix) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
