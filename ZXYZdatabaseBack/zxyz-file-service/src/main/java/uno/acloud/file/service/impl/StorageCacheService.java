package uno.acloud.file.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uno.acloud.dto.PersonalStorageUsage;
import uno.acloud.dto.ProjectStorageUsage;
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

    // ==================== sumActiveFileSizeByProjectIds ====================

    /**
     * 批量查询项目存储用量（项目列表页用）。
     *
     * <p>缓存键按 projectIds <b>排序后</b>拼接，保证 {@code [1,2]} 与 {@code [2,1]} 命中同一份缓存 ——
     * 否则同一批项目会因入参顺序不同而各自回源，缓存形同虚设。与
     * {@link #sumActiveFileSizeByTeamIds} 保持同一口径。</p>
     */
    public List<ProjectStorageUsage> sumActiveFileSizeByProjectIds(List<Long> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return List.of();
        }
        String sortedKey = projectIds.stream().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        String key = KEY_PREFIX + "project:" + sortedKey;
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<>() {});
            }
        } catch (Exception e) {
            log.warn("读取项目存储列表缓存失败: key={}", key, e);
        }
        List<ProjectStorageUsage> list = fileMapper.sumActiveFileSizeByProjectIds(projectIds);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(list), CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入项目存储列表缓存失败: key={}", key, e);
        }
        return list;
    }

    // ==================== Cache invalidation ====================

    /**
     * 失效所有存储统计缓存（无 scope 信息可用时的兜底）。
     * <p>使用 SCAN + DELETE 模式，30s TTL 兜底清理遗漏 key。</p>
     * <p>⚠️ 这是<b>粗粒度</b>失效：会把所有 space/team/user 的缓存一并删掉。
     * 已知受影响 scope 的调用方应改用 {@link #invalidateStorageScope}
     * （F12：避免「任何一次文件变更都清空整个命名空间」导致命中率趋近 0）。</p>
     */
    public void invalidateAllStorageCaches() {
        deleteByPattern(KEY_PREFIX + "*");
    }

    /**
     * 按受影响 scope <b>精确</b>失效存储统计缓存（F12，P3 缓存粒度）。
     *
     * <h2>修复前的缺陷</h2>
     * <p>任何一次文件变更都调用 {@link #invalidateAllStorageCaches()}，把整个
     * {@code file:storage:*} 命名空间 SCAN + DELETE 掉 —— 包括 rename/move 这类<b>不改容量</b>
     * 的操作。写热点下全体 scope 的缓存被反复清空，命中率趋近 0，且每次变更都触发一次 SCAN 风暴。
     * 而缓存 key 本身就带着 scope 维度（{@code sum:{spaceType}:{teamId}:{userId}:{projectId}}、
     * {@code personal:{ids}}、{@code team:{ids}}、{@code project:{ids}}），本可以精确失效。</p>
     *
     * <h2>为什么「单值键精确删 + 集合键精确匹配」才是完备的</h2>
     * <p>某个 scope（如 user=U）的缓存只有两种可能形态：</p>
     * <ol>
     *   <li><b>单值键</b> {@code sum:…}：userId/teamId/projectId 字段就是标识符
     *       ⇒ 直接按<b>拼好的 key</b> DEL，O(1)、无需 SCAN；</li>
     *   <li><b>集合键</b> {@code personal:{1,2,3}} / {@code team:{…}} / {@code project:{…}}
     *       / {@code personal-list:{…}}：键由<b>任意 ID 集合</b>拼成，同一 scope 会出现在
     *       无数个不同键里，无法用一条 DEL 命中。</li>
     * </ol>
     * <p>第 2 类必须遍历 key，但<b>绝不能用 glob 近似</b>：例如用
     * {@code file:storage:personal:*1,*} 匹配 userId=1 会误伤 userId=11/21 等
     * （子串匹配陷阱），把它们一起删掉 —— 比不删更糟（变成另一种粗粒度）。
     * 因此这里把键的 ID 段<b>按逗号拆开做精确成员判定</b>，只删真正含受影响 ID 的键。</p>
     *
     * <p>结果：受影响 scope 的缓存被完整失效（完备性），其余 scope 的缓存<b>全部保留</b>
     * —— 这正是「失效收敛到受影响 scope」的语义。</p>
     *
     * @param spaceType  空间类型（1 个人 / 2 团队 / 3 项目）；null 表示不限定，按下列 id 逐个尝试
     * @param teamId     团队 ID（团队空间变更时给出）
     * @param projectId  项目 ID（项目空间变更时给出）
     * @param userId     用户 ID（个人空间变更时给出）
     */
    public void invalidateStorageScope(Integer spaceType, Long teamId, Long projectId, Long userId) {
        // ① 单值 sum 键：四种可能的 scope 参数组合都是「已拼好的确定 key」，逐个直接 DEL。
        deleteKeys(buildSumKeysForScope(spaceType, teamId, projectId, userId));

        // ② 集合型键：遍历，按「逗号拆分后的精确成员」判定，绝不做子串匹配。
        invalidateCollectionKeysContaining(userId, teamId, projectId);
    }

    /**
     * 构造与 {@link #sumActiveFileSize} 完全同形的单值 key 列表。
     * <p>⚠️ 必须与 {@code sumActiveFileSize} 的拼法逐字一致（含 {@code safe()} 的 0 兜底），
     * 否则会「删了一个不存在的 key」而让真正的缓存留着不动 —— 静默失效失败。</p>
     */
    private List<String> buildSumKeysForScope(Integer spaceType, Long teamId, Long projectId, Long userId) {
        List<String> keys = new ArrayList<>(4);
        // 与 sumActiveFileSize 的参数口径一致：个人空间 teamId/spaceType/projectId 为 null 或 1/2/3
        keys.add(sumKey(spaceType, teamId, userId, projectId));
        // 个人空间：spaceType=1
        keys.add(sumKey(uno.acloud.common.FileSpaceType.PERSONAL, null, userId, null));
        // 团队空间：spaceType=2 + teamId
        if (teamId != null) {
            keys.add(sumKey(uno.acloud.common.FileSpaceType.TEAM, teamId, null, null));
        }
        // 项目空间：spaceType=3 + projectId
        if (projectId != null) {
            keys.add(sumKey(uno.acloud.common.FileSpaceType.PROJECT, null, null, projectId));
        }
        return keys.stream().distinct().toList();
    }

    /** 与 {@link #sumActiveFileSize} 的 key 拼法逐字一致。 */
    private String sumKey(Integer spaceType, Long teamId, Long userId, Long projectId) {
        return KEY_PREFIX + "sum:" + safe(spaceType) + ":" + safe(teamId)
                + ":" + safe(userId) + ":" + safe(projectId);
    }

    /**
     * 精确失效「ID 集合型」缓存键：只删 ID 列表中<b>确实包含</b>受影响 ID 的键。
     * <p>按逗号拆分后逐一相等比较，避免子串误伤（见 {@link #invalidateStorageScope} 的说明）。</p>
     */
    private void invalidateCollectionKeysContaining(Long userId, Long teamId, Long projectId) {
        List<Long> targets = new ArrayList<>(3);
        if (userId != null) {
            targets.add(userId);
        }
        if (teamId != null) {
            targets.add(teamId);
        }
        if (projectId != null) {
            targets.add(projectId);
        }
        if (targets.isEmpty()) {
            return;
        }
        for (String prefix : List.of("personal:", "personal-list:", "team:", "project:")) {
            deleteByPatternExactMembers(KEY_PREFIX + prefix, targets);
        }
    }

    /**
     * 遍历匹配 {@code prefix*} 的键，仅删除其 ID 段（{@code prefix} 之后的部分）按逗号拆分后
     * <b>精确包含</b>任一目标 ID 的键。
     */
    private void deleteByPatternExactMembers(String prefix, List<Long> targets) {
        try {
            ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(100).build();
            List<String> batch = new ArrayList<>(64);
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    String key = cursor.next();
                    if (keyMatchesExactMember(key, prefix, targets)) {
                        batch.add(key);
                        if (batch.size() >= 500) {
                            redisTemplate.delete(batch);
                            batch.clear();
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                redisTemplate.delete(batch);
            }
        } catch (Exception e) {
            log.warn("按 scope 清理集合型存储缓存失败: prefix={}", prefix, e);
        }
    }

    /**
     * 判定键的 ID 段是否精确包含任一目标 ID。
     * <p>ID 段格式为逗号分隔的数字串（如 {@code personal:7,8,9}）。
     * 用「拆分为字符串后逐一 equals」而不是 {@code contains} —— 后者会让 userId=1 命中 userId=11。</p>
     */
    private boolean keyMatchesExactMember(String key, String prefix, List<Long> targets) {
        if (key == null || !key.startsWith(prefix)) {
            return false;
        }
        String idPart = key.substring(prefix.length());
        if (idPart.isEmpty()) {
            return false;
        }
        List<String> members = List.of(idPart.split(",", -1));
        for (Long target : targets) {
            if (members.contains(String.valueOf(target))) {
                return true;
            }
        }
        return false;
    }

    private void deleteKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("删除存储统计缓存失败: keys={}", keys, e);
        }
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
