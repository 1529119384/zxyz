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

    /**
     * 按前缀失效。
     *
     * <p><b>为什么保留 O(n) 遍历（B-20 结论）</b>：Caffeine 的 {@code Cache} 没有
     * 「按键前缀批量失效」的原生支持，能 O(1) 做到这件事的前提是把 key 组织成
     * 两级结构（{@code Cache<Long, Cache<String, Boolean>>}，外层按 teamId）。
     * 但本类的 key 还带 userId（{@code teamId:userId:permissionCode}），
     * 而 {@link #invalidateMember} 需要「失效某个 team 下某个成员的全部 code」、
     * {@link #invalidateTeam} 需要「失效整个 team」—— 两者是不同的切分维度，
     * 两级结构只能优化其中一个。改造会把一处遍历换成另一处遍历，
     * 却让「key 拼装」这条鉴权语义散到多层，收益不抵复杂度。</p>
     *
     * <p><b>量级前提（可接受的理由）</b>：{@code maximumSize=10000} 是硬上限，
     * 每次遍历至多 1 万个 String key 的 {@code startsWith} 比较（微秒级）。
     * 该操作由 team-service 的权限变更广播触发，属低频管理动作，
     * 而非每请求路径。团队规模增长到万级以上、或权限变更变成高频操作时，
     * 再按「外层按 teamId 分层 + 内层按 userId 分层」重构。</p>
     */
    private void removeByPrefix(String prefix) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }
}
