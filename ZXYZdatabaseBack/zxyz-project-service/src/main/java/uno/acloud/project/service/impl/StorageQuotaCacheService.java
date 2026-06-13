package uno.acloud.project.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * StorageQuotaService 的 Redis 缓存层。
 * <p>缓存远程 HTTP 调用结果以减少 checkUploadQuota 的网络往返。
 * 所有读写均 try/catch 兜底，Redis 故障时静默降级到直接调用。</p>
 */
@Slf4j
@Service
public class StorageQuotaCacheService {

    private static final Duration TEAM_STORAGE_LIMIT_TTL = Duration.ofMinutes(10);
    private static final Duration TEAM_MEMBER_LIST_TTL = Duration.ofMinutes(5);
    private static final Duration SYSTEM_ADMIN_IDS_TTL = Duration.ofMinutes(5);
    private static final Duration USAGE_TTL = Duration.ofSeconds(30);
    private static final Duration USER_TEAM_IDS_TTL = Duration.ofMinutes(5);
    private static final Duration SYSTEM_ROLES_TTL = Duration.ofMinutes(5);

    private static final String KEY_PREFIX = "zxyz:project:quota:";
    private static final String TEAM_STORAGE_LIMIT_KEY = KEY_PREFIX + "team:storage:";
    private static final String TEAM_MEMBER_LIST_KEY = KEY_PREFIX + "team:members:";
    private static final String SYSTEM_ADMIN_IDS_KEY = KEY_PREFIX + "system:admin:ids";
    private static final String USAGE_ACTIVE_KEY_PREFIX = KEY_PREFIX + "usage:active:";
    private static final String USAGE_PERSONAL_KEY_PREFIX = KEY_PREFIX + "usage:personal:";
    private static final String USER_TEAM_IDS_KEY = KEY_PREFIX + "user:teamIds:";
    private static final String SYSTEM_ROLES_KEY = KEY_PREFIX + "user:systemRoles:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final TeamServiceClient teamServiceClient;
    private final FileServiceClient fileServiceClient;

    public StorageQuotaCacheService(StringRedisTemplate redisTemplate,
                                    ObjectMapper objectMapper,
                                    TeamServiceClient teamServiceClient,
                                    FileServiceClient fileServiceClient) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.teamServiceClient = teamServiceClient;
        this.fileServiceClient = fileServiceClient;
    }

    // ==================== Team storage limit ====================

    /**
     * 获取团队存储上限，优先读缓存（TTL 10 分钟）。
     */
    public Long getTeamStorageLimit(Long teamId) {
        String key = TEAM_STORAGE_LIMIT_KEY + teamId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return "null".equals(cached) ? null : Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取团队存储上限缓存失败: teamId={}", teamId, e);
        }
        Long limit = teamServiceClient.getTeamStorageLimit(teamId);
        try {
            redisTemplate.opsForValue().set(key, limit == null ? "null" : String.valueOf(limit), TEAM_STORAGE_LIMIT_TTL);
        } catch (Exception e) {
            log.warn("写入团队存储上限缓存失败: teamId={}", teamId, e);
        }
        return limit;
    }

    // ==================== Team member list ====================

    /**
     * 获取团队成员用户 ID 列表，优先读缓存（TTL 5 分钟）。
     */
    public List<Long> listTeamMemberUserIds(Long teamId) {
        String key = TEAM_MEMBER_LIST_KEY + teamId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取团队成员列表缓存失败: teamId={}", teamId, e);
        }
        List<Long> members = teamServiceClient.listTeamMemberUserIds(teamId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(members), TEAM_MEMBER_LIST_TTL);
        } catch (Exception e) {
            log.warn("写入团队成员列表缓存失败: teamId={}", teamId, e);
        }
        return members;
    }

    // ==================== System admin IDs ====================

    /**
     * 获取系统管理员 ID 列表，优先读缓存（TTL 5 分钟）。
     */
    public List<Long> listSystemAdminUserIds() {
        String key = SYSTEM_ADMIN_IDS_KEY;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取系统管理员列表缓存失败", e);
        }
        List<Long> adminIds = teamServiceClient.listSystemAdminUserIds();
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(adminIds), SYSTEM_ADMIN_IDS_TTL);
        } catch (Exception e) {
            log.warn("写入系统管理员列表缓存失败", e);
        }
        return adminIds;
    }

    // ==================== Active file size (usage) ====================

    /**
     * 查询活跃文件总大小，优先读缓存（TTL 30 秒）。
     */
    public long sumActiveFileSize(Long userId, Long teamId, Integer spaceType, Long projectId) {
        String key = USAGE_ACTIVE_KEY_PREFIX + safeInt(spaceType)
                + ":" + safeLong(teamId)
                + ":" + safeLong(userId)
                + ":" + safeLong(projectId);
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取活跃文件大小缓存失败: key={}", key, e);
        }
        long size = fileServiceClient.sumActiveFileSize(userId, teamId, spaceType, projectId);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(size), USAGE_TTL);
        } catch (Exception e) {
            log.warn("写入活跃文件大小缓存失败: key={}", key, e);
        }
        return size;
    }

    // ==================== Personal storage by users ====================

    /**
     * 查询指定用户列表的个人存储用量总和，优先读缓存（TTL 30 秒）。
     */
    public long sumPersonalStorageByUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        String sortedKey = userIds.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        String key = USAGE_PERSONAL_KEY_PREFIX + sortedKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return Long.parseLong(cached);
            }
        } catch (Exception e) {
            log.warn("读取个人存储用量缓存失败: key={}", key, e);
        }
        long size = fileServiceClient.sumPersonalStorageByUsers(userIds);
        try {
            redisTemplate.opsForValue().set(key, String.valueOf(size), USAGE_TTL);
        } catch (Exception e) {
            log.warn("写入个人存储用量缓存失败: key={}", key, e);
        }
        return size;
    }

    // ==================== User team IDs ====================

    /**
     * 获取用户所属的团队 ID 列表，优先读缓存（TTL 5 分钟）。
     */
    public List<Long> listUserTeamIds(Long userId) {
        String key = USER_TEAM_IDS_KEY + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取用户团队列表缓存失败: userId={}", userId, e);
        }
        List<Long> teamIds = teamServiceClient.listUserTeamIds(userId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(teamIds), USER_TEAM_IDS_TTL);
        } catch (Exception e) {
            log.warn("写入用户团队列表缓存失败: userId={}", userId, e);
        }
        return teamIds;
    }

    // ==================== System roles ====================

    /**
     * 获取用户的系统角色列表，优先读缓存（TTL 5 分钟）。
     */
    public List<String> getSystemRolesByUserId(Long userId) {
        String key = SYSTEM_ROLES_KEY + userId;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取用户系统角色缓存失败: userId={}", userId, e);
        }
        List<String> roles = teamServiceClient.getSystemRolesByUserId(userId);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(roles), SYSTEM_ROLES_TTL);
        } catch (Exception e) {
            log.warn("写入用户系统角色缓存失败: userId={}", userId, e);
        }
        return roles;
    }

    // ==================== Cache invalidation ====================

    /**
     * 失效所有存储用量缓存（文件变更时调用）。
     * <p>使用 SCAN + DELETE 模式，短 TTL 兜底清理遗漏 key。</p>
     */
    public void invalidateAllUsageCaches() {
        deleteByPattern(USAGE_ACTIVE_KEY_PREFIX + "*");
        deleteByPattern(USAGE_PERSONAL_KEY_PREFIX + "*");
    }

    /**
     * 失效指定团队的存储相关缓存。
     */
    public void invalidateTeamCache(Long teamId) {
        if (teamId == null) {
            return;
        }
        redisTemplate.delete(TEAM_STORAGE_LIMIT_KEY + teamId);
        redisTemplate.delete(TEAM_MEMBER_LIST_KEY + teamId);
    }

    /**
     * 失效指定用户的团队列表缓存（团队成员变更时调用）。
     */
    public void invalidateUserTeamIdsCache(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.delete(USER_TEAM_IDS_KEY + userId);
    }

    /**
     * 失效系统管理员缓存。
     */
    public void invalidateSystemAdminCache() {
        redisTemplate.delete(SYSTEM_ADMIN_IDS_KEY);
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
            log.warn("清理存储用量缓存失败: pattern={}", pattern, e);
        }
    }

    private String safeLong(Long value) {
        return value == null ? "0" : String.valueOf(value);
    }

    private String safeInt(Integer value) {
        return value == null ? "0" : String.valueOf(value);
    }
}
