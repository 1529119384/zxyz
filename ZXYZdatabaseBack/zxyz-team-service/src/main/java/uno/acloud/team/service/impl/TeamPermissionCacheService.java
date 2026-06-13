package uno.acloud.team.service.impl;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 团队权限缓存服务。
 * <p>使用 Spring Cache 抽象（{@code @Cacheable} / {@code @CacheEvict}），
 * 底层由 {@code CacheConfig} 中的 {@code RedisCacheManager} 驱动，
 * 缓存名 {@code team-permission}，TTL 5 分钟。</p>
 * <p>权限查询通过 {@link #checkPermission} 实现 cache-aside 模式：
 * 缓存命中时直接返回，未命中时调用 {@code fallback} 查询数据库并缓存结果。
 * 角色/成员变更时通过 {@code @CacheEvict(allEntries=true)} 批量清除。
 * 5 分钟 TTL 兜底清理因竞态遗漏的旧值。</p>
 */
@Service
public class TeamPermissionCacheService {

    private static final String CACHE_NAME = "team-permission";

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
    @Cacheable(value = CACHE_NAME,
            key = "#teamId + ':' + #userId + ':' + #permissionCode",
            condition = "#teamId != null && #userId != null && #permissionCode != null && !#permissionCode.isEmpty()")
    public boolean checkPermission(Long teamId, Long userId, String permissionCode,
                                   Supplier<Boolean> fallback) {
        return fallback.get();
    }

    /**
     * 失效指定团队的所有权限缓存（角色定义变更、权限分配变更时调用）。
     * <p>使用 {@code allEntries=true} 清除整个 team-permission 缓存，
     * 精确到团队级别的模式匹配在 Spring Cache 中不可用，5 分钟 TTL 兜底。</p>
     */
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void evictTeam(Long teamId) {
        // 所有缓存清除由 @CacheEvict 代理完成
    }

    /**
     * 失效指定成员的权限缓存（成员角色变更时调用）。
     * <p>使用 {@code allEntries=true}，与 {@link #evictTeam} 同理。</p>
     */
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public void evictMember(Long teamId, Long userId) {
        // 所有缓存清除由 @CacheEvict 代理完成
    }
}
