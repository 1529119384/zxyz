package uno.acloud.file.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.TeamStorageUsage;
import uno.acloud.file.infrastructure.mapper.FileMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * InternalStorageController 的 Redis 缓存层。
 * <p>缓存 FileMapper 的存储统计聚合查询（30 秒 TTL），避免每次请求都执行 SUM 全表扫描。
 * 所有读写均 try/catch 兜底，Redis 故障时静默降级到直接查询。</p>
 */
@Slf4j
@Service
public class StorageCacheService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final String KEY_PREFIX = "file:storage:";

    private final FileMapper fileMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public StorageCacheService(FileMapper fileMapper,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.fileMapper = fileMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    // ==================== sumActiveFileSize ====================

    public long sumActiveFileSize(Long userId, Long teamId, Integer spaceType, Long projectId) {
        String key = KEY_PREFIX + "sum:" + safe(spaceType) + ":" + safe(teamId)
                + ":" + safe(userId) + ":" + safe(projectId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取存储用量缓存失败: key={}", key, e);
        }
        long sum = fileMapper.sumActiveFileSize(userId, teamId, spaceType, projectId);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(sum), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入存储用量缓存失败: key={}", key, e);
        }
        return sum;
    }

    // ==================== sumPersonalStorageByUsers ====================

    public long sumPersonalStorageByUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        String sortedKey = userIds.stream().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        String key = KEY_PREFIX + "personal:" + sortedKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取个人存储用量缓存失败: key={}", key, e);
        }
        long sum = fileMapper.sumPersonalStorageByUsers(userIds);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(sum), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入个人存储用量缓存失败: key={}", key, e);
        }
        return sum;
    }

    // ==================== listPersonalStorageUsageByUsers ====================

    public List<PersonalStorageUsage> listPersonalStorageUsageByUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        String sortedKey = userIds.stream().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        String key = KEY_PREFIX + "personal-list:" + sortedKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取个人存储列表缓存失败: key={}", key, e);
        }
        List<PersonalStorageUsage> list = fileMapper.listPersonalStorageUsageByUsers(userIds);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(list), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入个人存储列表缓存失败: key={}", key, e);
        }
        return list;
    }

    // ==================== sumActiveFileSizeByTeamIds ====================

    public List<TeamStorageUsage> sumActiveFileSizeByTeamIds(List<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        String sortedKey = teamIds.stream().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        String key = KEY_PREFIX + "team:" + sortedKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取团队存储列表缓存失败: key={}", key, e);
        }
        List<TeamStorageUsage> list = fileMapper.sumActiveFileSizeByTeamIds(teamIds);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(list), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入团队存储列表缓存失败: key={}", key, e);
        }
        return list;
    }

    // ==================== Cache invalidation ====================

    /**
     * 失效所有存储统计缓存（文件上传/删除/恢复时调用）。
     * <p>使用 SCAN + DELETE 模式，30s TTL 兜底清理遗漏 key。</p>
     */
    public void invalidateAllStorageCaches() {
        deleteByPattern(KEY_PREFIX + "*");
    }

    // ==================== Helpers ====================

    private void deleteByPattern(String pattern) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            List<String> batch = new ArrayList<>(64);
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    batch.add(cursor.next());
                    if (batch.size() >= 500) {
                        redisTemplate.delete(batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        } catch (Exception e) {
            log.warn("清理存储统计缓存失败: pattern={}", pattern, e);
        }
    }

    private String safe(Object value) {
        return value == null ? "0" : String.valueOf(value);
    }
}
