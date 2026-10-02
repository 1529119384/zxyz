package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.file.infrastructure.mapper.FileMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageCacheServiceTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StorageCacheService service;

    @BeforeEach
    void setUp() {
        service = new StorageCacheService(fileMapper, redisTemplate, objectMapper);
    }

    // ---- sumActiveFileSize tests ----

    @Test
    void sumActiveFileSize_shouldReturnCachedValueOnHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("5000");

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(5000L, result);
        verifyNoInteractions(fileMapper);
    }

    @Test
    void sumActiveFileSize_shouldQueryDbOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(fileMapper.sumActiveFileSize(100L, 10L, 2, null)).thenReturn(7500L);

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(7500L, result);
        verify(fileMapper).sumActiveFileSize(100L, 10L, 2, null);
    }

    @Test
    void sumActiveFileSize_shouldDegradeOnRedisReadFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        when(fileMapper.sumActiveFileSize(100L, 10L, 2, null)).thenReturn(3000L);

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(3000L, result);
        verify(fileMapper).sumActiveFileSize(100L, 10L, 2, null);
    }

    @Test
    void sumActiveFileSize_shouldDegradeOnRedisWriteFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(fileMapper.sumActiveFileSize(100L, 10L, 2, null)).thenReturn(8000L);
        doThrow(new RuntimeException("Redis write failed"))
                .when(valueOperations).set(anyString(), anyString(), any());

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(8000L, result);
    }

    // ---- sumPersonalStorageByUsers tests ----

    @Test
    void sumPersonalStorageByUsers_shouldReturnZeroForEmptyList() {
        assertEquals(0L, service.sumPersonalStorageByUsers(List.of()));
    }

    @Test
    void sumPersonalStorageByUsers_shouldReturnZeroForNull() {
        assertEquals(0L, service.sumPersonalStorageByUsers(null));
    }

    @Test
    void sumPersonalStorageByUsers_shouldReturnCachedValueOnHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("12000");

        long result = service.sumPersonalStorageByUsers(List.of(1L, 2L));

        assertEquals(12000L, result);
        verifyNoInteractions(fileMapper);
    }

    @Test
    void sumPersonalStorageByUsers_shouldQueryDbOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(fileMapper.sumPersonalStorageByUsers(List.of(1L, 2L))).thenReturn(9000L);

        long result = service.sumPersonalStorageByUsers(List.of(1L, 2L));

        assertEquals(9000L, result);
        verify(fileMapper).sumPersonalStorageByUsers(List.of(1L, 2L));
    }

    // ---- invalidateAllStorageCaches tests ----

    @SuppressWarnings("unchecked")
    @Test
    void invalidateAllStorageCaches_shouldScanAndDelete() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("file:storage:sum:2:10:100:0");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.invalidateAllStorageCaches());

        verify(redisTemplate).scan(any(ScanOptions.class));
        verify(redisTemplate).delete(anyCollection());
    }

    @Test
    void invalidateAllStorageCaches_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> service.invalidateAllStorageCaches());
    }

    // ============ F12（P3 缓存粒度）：按 scope 精确失效 ============

    @SuppressWarnings("unchecked")
    private Cursor<String> cursorOver(String... keys) {
        Cursor<String> cursor = mock(Cursor.class);
        Boolean[] hasNext = new Boolean[keys.length + 1];
        for (int i = 0; i < keys.length; i++) {
            hasNext[i] = true;
        }
        hasNext[keys.length] = false;
        when(cursor.hasNext()).thenReturn(hasNext[0], java.util.Arrays.copyOfRange(hasNext, 1, hasNext.length));
        if (keys.length == 1) {
            when(cursor.next()).thenReturn(keys[0]);
        } else if (keys.length > 1) {
            when(cursor.next()).thenReturn(keys[0], java.util.Arrays.copyOfRange(keys, 1, keys.length));
        }
        return cursor;
    }

    /**
     * F12 核心：{@code invalidateStorageScope} 必须删掉<b>受影响 scope</b> 的单值 sum 键与
     * 集合型键，同时<b>保留</b>其它 scope 的键。
     *
     * <h2>修复前的缺陷</h2>
     * <p>任何一次文件变更都调用 {@code invalidateAllStorageCaches()}，把整个 {@code file:storage:*}
     * 命名空间清空 —— 包括 rename/move 这类不改容量的操作。写热点下全体 scope 的命中率趋近 0，
     * 且每次变更都触发一次 SCAN 风暴。</p>
     */
    @SuppressWarnings("unchecked")
    @Test
    void invalidateStorageScope_deletesOnlyAffectedScopeKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        // 三个集合型键：personal 含 user=7；team 含 team=10；project 含 project=99
        when(cursor.hasNext()).thenReturn(true, true, true, false);
        when(cursor.next()).thenReturn(
                "file:storage:personal:7,8",
                "file:storage:team:10,20",
                "file:storage:project:99");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.invalidateStorageScope(2, 10L, null, 7L));

        // 单值 sum 键直接 DEL（精确 key，无需 SCAN）
        verify(redisTemplate).delete(argThat((java.util.Collection<String> keys) ->
                keys != null
                        && keys.contains("file:storage:sum:2:10:7:0")
                        && keys.contains("file:storage:sum:1:0:7:0")));
    }

    /**
     * F12 关键反例：必须按<b>逗号拆分后的精确成员</b>判定，绝不能用子串匹配 ——
     * 否则删 userId=1 的缓存会连带删掉 userId=11 / 21 的（子串陷阱），
     * 把「精确失效」退化成另一种粗粒度失效，比不删更糟。
     */
    @SuppressWarnings("unchecked")
    @Test
    void invalidateStorageScope_mustNotMatchIdsBySubstring() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(
                "file:storage:personal:11",      // 含 "1" 但不含成员 1 ⇒ 必须保留
                "file:storage:personal:1,2");    // 含成员 1 ⇒ 必须删除
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.invalidateStorageScope(1, null, null, 1L);

        // 扫描本身允许多次（4 种集合前缀各扫一次），但被删的键必须**只含**真正的成员键。
        verify(redisTemplate, atLeastOnce()).delete(argThat((java.util.Collection<String> keys) ->
                keys != null
                        && keys.contains("file:storage:personal:1,2")
                        && !keys.contains("file:storage:personal:11")));
    }

    /** scope 参数全为 null 时不得 NPE，且不应删任何集合型键（无目标可判）。 */
    @Test
    void invalidateStorageScope_withNoIds_shouldNotThrow() {
        assertDoesNotThrow(() -> service.invalidateStorageScope(null, null, null, null));
    }

    /** Redis 抖动时按 scope 失效必须静默降级（与全量版同一容错口径），不得影响业务返回值。 */
    @Test
    void invalidateStorageScope_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.delete(anyCollection())).thenThrow(new RuntimeException("Redis down"));
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> service.invalidateStorageScope(1, null, null, 7L));
    }
}
