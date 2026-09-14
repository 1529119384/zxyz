package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import uno.acloud.common.permission.TeamPermissionLocalCache;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamPermissionCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private TeamPermissionCacheService service;

    @BeforeEach
    void setUp() {
        service = new TeamPermissionCacheService(redisTemplate);
    }

    // ---- evictTeam tests ----

    @SuppressWarnings("unchecked")
    @Test
    void evictTeam_shouldScanAndDeleteMatchingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("team-permission::42:101:manage", "team-permission::42:101:view");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.evictTeam(42L);

        verify(redisTemplate).scan(any(ScanOptions.class));
        verify(redisTemplate).delete(anyCollection());
    }

    @SuppressWarnings("unchecked")
    @Test
    void evictTeam_shouldHandleNoMatchingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.evictTeam(42L));

        verify(redisTemplate, never()).delete(anyCollection());
    }

    // ---- evictMember tests ----

    @SuppressWarnings("unchecked")
    @Test
    void evictMember_shouldScanAndDeleteMatchingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("team-permission::42:101:manage");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.evictMember(42L, 101L);

        verify(redisTemplate).scan(any(ScanOptions.class));
        verify(redisTemplate).delete(anyCollection());
    }

    // ---- Redis failure degradation ----

    @Test
    void evictTeam_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.evictTeam(42L));
    }

    @Test
    void evictMember_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.evictMember(42L, 101L));
    }

    // ---- 失效广播：本服务是 zxyz:team-permission:changed 的唯一发布方 ----
    // 这两个用例是「消费方本地缓存能秒级失效」的唯一守护。若哪天有人删掉发布逻辑，
    // 线上表现会是「改了权限要过 5 分钟才生效」，很难定位 —— 所以必须在这里拦住。

    @SuppressWarnings("unchecked")
    @Test
    void evictTeam_shouldBroadcastInvalidation() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.evictTeam(42L);

        verify(redisTemplate).convertAndSend(TeamPermissionLocalCache.INVALIDATION_TOPIC, "42");
    }

    @SuppressWarnings("unchecked")
    @Test
    void evictMember_shouldBroadcastInvalidationWithUserId() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.evictMember(42L, 101L);

        verify(redisTemplate).convertAndSend(TeamPermissionLocalCache.INVALIDATION_TOPIC, "42:101");
    }

    @Test
    void evictTeam_shouldNotThrowWhenBroadcastFails() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.evictTeam(42L));
    }

    // ---- checkPermission (delegates to fallback via @Cacheable) ----

    @Test
    void checkPermission_shouldCallFallback() {
        Supplier<Boolean> fallback = () -> true;
        boolean result = service.checkPermission(1L, 1L, "test", fallback);
        assertTrue(result);
    }
}
