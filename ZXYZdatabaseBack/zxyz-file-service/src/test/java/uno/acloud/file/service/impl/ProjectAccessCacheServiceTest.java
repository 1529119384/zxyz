package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectAccessCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ProjectServiceAccessClient projectServiceAccessClient;

    private ProjectAccessCacheService service;

    @BeforeEach
    void setUp() {
        service = new ProjectAccessCacheService(redisTemplate, projectServiceAccessClient);
    }

    // ---- checkAccess tests ----

    @Test
    void checkAccess_shouldReturnDirectlyOnCacheHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("file:project-access:50:100")).thenReturn("1");

        assertDoesNotThrow(() -> service.checkAccess(50L, 100L));

        verifyNoInteractions(projectServiceAccessClient);
    }

    @Test
    void checkAccess_shouldCallRemoteOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("file:project-access:50:100")).thenReturn(null);

        assertDoesNotThrow(() -> service.checkAccess(50L, 100L));

        verify(projectServiceAccessClient).checkAccess(50L, 100L);
        verify(valueOperations).set("file:project-access:50:100", "1", 30, TimeUnit.SECONDS);
    }

    @Test
    void checkAccess_shouldCallRemoteWhenCachedValueIsNotOne() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("file:project-access:50:100")).thenReturn("0");

        assertDoesNotThrow(() -> service.checkAccess(50L, 100L));

        verify(projectServiceAccessClient).checkAccess(50L, 100L);
    }

    @Test
    void checkAccess_shouldDegradeGracefullyOnRedisReadFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.checkAccess(50L, 100L));

        // Should still call remote service
        verify(projectServiceAccessClient).checkAccess(50L, 100L);
    }

    @Test
    void checkAccess_shouldDegradeGracefullyOnRedisWriteFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("file:project-access:50:100")).thenReturn(null);
        doThrow(new RuntimeException("Redis write failed"))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        assertDoesNotThrow(() -> service.checkAccess(50L, 100L));

        // Remote call should still succeed
        verify(projectServiceAccessClient).checkAccess(50L, 100L);
    }

    @Test
    void checkAccess_shouldNotCacheOnRemoteFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("file:project-access:50:100")).thenReturn(null);
        doThrow(new RuntimeException("Access denied"))
                .when(projectServiceAccessClient).checkAccess(50L, 100L);

        assertThrows(RuntimeException.class, () -> service.checkAccess(50L, 100L));

        // Should NOT cache the result when remote call fails
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    // ---- evictProject tests ----

    @SuppressWarnings("unchecked")
    @Test
    void evictProject_shouldDeleteMatchingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("file:project-access:50:100", "file:project-access:50:200");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        service.evictProject(50L);

        verify(redisTemplate).delete(List.of("file:project-access:50:100", "file:project-access:50:200"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void evictProject_shouldHandleNoMatchingKeys() throws Exception {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(false);
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        assertDoesNotThrow(() -> service.evictProject(50L));

        verify(redisTemplate, never()).delete(any(List.class));
    }

    @Test
    void evictProject_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.evictProject(50L));
    }
}
