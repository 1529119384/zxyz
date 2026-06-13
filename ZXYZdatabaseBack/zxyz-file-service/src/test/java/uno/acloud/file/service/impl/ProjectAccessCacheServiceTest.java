package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
        verify(valueOperations).set("file:project-access:50:100", "1", 5, TimeUnit.MINUTES);
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

    @Test
    void evictProject_shouldDeleteMatchingKeys() {
        Set<String> keys = Set.of("file:project-access:50:100", "file:project-access:50:200");
        when(redisTemplate.keys("file:project-access:50:*")).thenReturn(keys);

        service.evictProject(50L);

        verify(redisTemplate).delete(keys);
    }

    @Test
    void evictProject_shouldHandleNoMatchingKeys() {
        when(redisTemplate.keys("file:project-access:50:*")).thenReturn(Set.of());

        assertDoesNotThrow(() -> service.evictProject(50L));

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void evictProject_shouldHandleNullKeySet() {
        when(redisTemplate.keys("file:project-access:50:*")).thenReturn(null);

        assertDoesNotThrow(() -> service.evictProject(50L));

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void evictProject_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.keys("file:project-access:50:*"))
                .thenThrow(new RuntimeException("Redis unavailable"));

        assertDoesNotThrow(() -> service.evictProject(50L));
    }
}
