package uno.acloud.file.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProjectAccessCacheService {

    private static final String KEY_PREFIX = "file:project-access:";
    private static final long CACHE_TTL_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;
    private final ProjectServiceAccessClient projectServiceAccessClient;

    public ProjectAccessCacheService(StringRedisTemplate redisTemplate,
                                     ProjectServiceAccessClient projectServiceAccessClient) {
        this.redisTemplate = redisTemplate;
        this.projectServiceAccessClient = projectServiceAccessClient;
    }

    /**
     * Check project access with Redis caching.
     * Cache key: file:project-access:{projectId}:{userId}
     * Cache value: "1" (access granted)
     * TTL: 30 seconds
     */
    public void checkAccess(Long projectId, Long userId) {
        String key = KEY_PREFIX + projectId + ":" + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if ("1".equals(cached)) {
                return; // cache hit — access previously verified
            }
        } catch (Exception e) {
            log.warn("Redis 读取项目访问缓存失败，降级为直接调用: projectId={}, userId={}", projectId, userId, e);
        }

        // cache miss or Redis error — call project-service
        projectServiceAccessClient.checkAccess(projectId, userId);

        // cache the positive result
        try {
            redisTemplate.opsForValue().set(key, "1", CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 写入项目访问缓存失败: projectId={}, userId={}", projectId, userId, e);
        }
    }

    /**
     * Invalidate access cache for a specific project (called when project members change).
     * Uses SCAN instead of KEYS to avoid blocking Redis.
     */
    public void evictProject(Long projectId) {
        String pattern = KEY_PREFIX + projectId + ":*";
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
                log.info("已清除项目访问缓存: projectId={}, keys={}", projectId, keysToDelete.size());
            }
        } catch (Exception e) {
            log.warn("Redis 清除项目访问缓存失败: projectId={}", projectId, e);
        }
    }
}
