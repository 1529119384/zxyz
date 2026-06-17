package uno.acloud.team.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 团队权限缓存服务。
 * <p>使用 StringRedisTemplate 直接操作 Redis，支持精确到团队/成员级别的 SCAN 失效。
 * 缓存名 {@code team-permission}，TTL 5 分钟。</p>
 * <p>权限查询通过 {@link #checkPermission} 实现 cache-aside 模式：
 * 缓存命中时直接返回，未命中时调用 {@code fallback} 查询数据库并缓存结果。
 * 角色/成员变更时通过 {@link #evictTeam}/{@link #evictMember} 精确失效。</p>
 */
@Slf4j
@Service
public class TeamPermissionCacheService {

    private static final String CACHE_PREFIX = "team-permission::";

    private final StringRedisTemplate redisTemplate;

    public TeamPermissionCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查团队权限（cache-aside 模式）。
     * <p>缓存命中时直接返回缓存值；未命中时调用 {@code fallback} 查询数据库，
     * 结果自动缓存（仅缓存 true/false，不缓存无效参数场景下的 fallback 调用）。</p>
     *
     * @param teamId         团队 ID
     * @param userId         用户 ID
     * @param permissionCode 权限编码
     * @param fallback       缓存未命中时的数据库查询逻辑
     * @return 权限检查结果
     */
    public boolean checkPermission(Long teamId, Long userId, String permissionCode,
                                   Supplier<Boolean> fallback) {
        if (teamId == null || userId == null || permissionCode == null || permissionCode.isEmpty()) {
            return fallback.get();
        }
        String key = CACHE_PREFIX + teamId + ":" + userId + ":" + permissionCode;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Boolean.parseBoolean(cached);
            }
        } catch (Exception e) {
            log.warn("Redis 读取团队权限缓存失败，降级为直接查询: teamId={}, userId={}", teamId, userId, e);
        }

        boolean result = fallback.get();

        try {
            redisTemplate.opsForValue().set(key, String.valueOf(result), 5, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis 写入团队权限缓存失败: teamId={}, userId={}", teamId, userId, e);
        }
        return result;
    }

    /**
     * 失效指定团队的所有权限缓存（角色定义变更、权限分配变更时调用）。
     * <p>使用 SCAN 精确匹配 {@code team-permission::{teamId}:*}，不影响其他团队。</p>
     */
    public void evictTeam(Long teamId) {
        String pattern = CACHE_PREFIX + teamId + ":*";
        evictByPattern(pattern);
        log.info("已清除团队权限缓存: teamId={}", teamId);
    }

    /**
     * 失效指定成员的权限缓存（成员角色变更时调用）。
     * <p>使用 SCAN 精确匹配 {@code team-permission::{teamId}:{userId}:*}。</p>
     */
    public void evictMember(Long teamId, Long userId) {
        String pattern = CACHE_PREFIX + teamId + ":" + userId + ":*";
        evictByPattern(pattern);
        log.info("已清除成员权限缓存: teamId={}, userId={}", teamId, userId);
    }

    private void evictByPattern(String pattern) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            List<String> keysToDelete = new ArrayList<>();
            try (var cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keysToDelete.add(cursor.next());
                }
            }
            if (!keysToDelete.isEmpty()) {
                redisTemplate.delete(keysToDelete);
            }
        } catch (Exception e) {
            log.warn("Redis 清除团队权限缓存失败: pattern={}", pattern, e);
        }
    }
}
