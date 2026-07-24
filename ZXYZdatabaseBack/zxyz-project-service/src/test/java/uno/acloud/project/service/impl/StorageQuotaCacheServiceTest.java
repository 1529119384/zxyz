package uno.acloud.project.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.common.config.ConfigGetter;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageQuotaCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private TeamServiceClient teamServiceClient;
    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private ConfigGetter configGetter;

    private ObjectMapper objectMapper;
    private StorageQuotaCacheService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        org.mockito.Mockito.when(configGetter.getInt("app.cache.team-permission-ttl-minutes", 5)).thenReturn(5);
        org.mockito.Mockito.when(configGetter.getInt("app.cache.storage-usage-ttl-seconds", 30)).thenReturn(30);
        service = new StorageQuotaCacheService(redisTemplate, objectMapper,
                teamServiceClient, fileServiceClient, configGetter);
    }

    // ---- getTeamStorageLimit tests ----

    @Test
    void getTeamStorageLimit_shouldReturnCachedValueOnHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:team:storage:10")).thenReturn("1073741824");

        Long result = service.getTeamStorageLimit(10L);

        assertEquals(1073741824L, result);
        verifyNoInteractions(teamServiceClient);
    }

    @Test
    void getTeamStorageLimit_shouldCallRemoteOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:team:storage:10")).thenReturn(null);
        when(teamServiceClient.getTeamStorageLimit(10L)).thenReturn(2048L);

        Long result = service.getTeamStorageLimit(10L);

        assertEquals(2048L, result);
        verify(teamServiceClient).getTeamStorageLimit(10L);
        verify(valueOperations).set(eq("zxyz:project:quota:team:storage:10"), eq("2048"), any());
    }

    @Test
    void getTeamStorageLimit_shouldDegradeOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));
        when(teamServiceClient.getTeamStorageLimit(10L)).thenReturn(512L);

        Long result = service.getTeamStorageLimit(10L);

        assertEquals(512L, result);
        verify(teamServiceClient).getTeamStorageLimit(10L);
    }

    @Test
    void getTeamStorageLimit_shouldHandleCachedNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:team:storage:10")).thenReturn("null");

        Long result = service.getTeamStorageLimit(10L);

        assertNull(result);
        verifyNoInteractions(teamServiceClient);
    }

    // ---- listTeamMemberUserIds tests ----

    @Test
    void listTeamMemberUserIds_shouldReturnCachedValueOnHit() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:team:members:10"))
                .thenReturn("[100,200,300]");

        List<Long> result = service.listTeamMemberUserIds(10L);

        assertEquals(3, result.size());
        assertEquals(100L, result.get(0));
        assertEquals(200L, result.get(1));
        assertEquals(300L, result.get(2));
        verifyNoInteractions(teamServiceClient);
    }

    @Test
    void listTeamMemberUserIds_shouldCallRemoteOnCacheMiss() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:team:members:10")).thenReturn(null);
        when(teamServiceClient.listTeamMemberUserIds(10L)).thenReturn(List.of(100L, 200L));

        List<Long> result = service.listTeamMemberUserIds(10L);

        assertEquals(2, result.size());
        verify(teamServiceClient).listTeamMemberUserIds(10L);
    }

    @Test
    void listTeamMemberUserIds_shouldDegradeOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        when(teamServiceClient.listTeamMemberUserIds(10L)).thenReturn(List.of(100L));

        List<Long> result = service.listTeamMemberUserIds(10L);

        assertEquals(1, result.size());
        assertEquals(100L, result.get(0));
    }

    // ---- sumActiveFileSize tests ----

    @Test
    void sumActiveFileSize_shouldReturnCachedValueOnHit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("5000");

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(5000L, result);
        verifyNoInteractions(fileServiceClient);
    }

    @Test
    void sumActiveFileSize_shouldCallRemoteOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(fileServiceClient.sumActiveFileSize(100L, 10L, 2, null)).thenReturn(7500L);

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(7500L, result);
        verify(fileServiceClient).sumActiveFileSize(100L, 10L, 2, null);
    }

    @Test
    void sumActiveFileSize_shouldDegradeOnRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis unavailable"));
        when(fileServiceClient.sumActiveFileSize(100L, 10L, 2, null)).thenReturn(3000L);

        long result = service.sumActiveFileSize(100L, 10L, 2, null);

        assertEquals(3000L, result);
    }

    // ---- sumPersonalStorageByUsers tests ----

    @Test
    void sumPersonalStorageByUsers_shouldReturnZeroForEmptyList() {
        long result = service.sumPersonalStorageByUsers(List.of());
        assertEquals(0L, result);
    }

    @Test
    void sumPersonalStorageByUsers_shouldReturnZeroForNull() {
        long result = service.sumPersonalStorageByUsers(null);
        assertEquals(0L, result);
    }

    // ---- listSystemAdminUserIds tests ----

    @Test
    void listSystemAdminUserIds_shouldReturnCachedValueOnHit() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:system:admin:ids")).thenReturn("[1,2]");

        List<Long> result = service.listSystemAdminUserIds();

        assertEquals(2, result.size());
        verifyNoInteractions(teamServiceClient);
    }

    @Test
    void listSystemAdminUserIds_shouldCallRemoteOnCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zxyz:project:quota:system:admin:ids")).thenReturn(null);
        when(teamServiceClient.listSystemAdminUserIds()).thenReturn(List.of(1L));

        List<Long> result = service.listSystemAdminUserIds();

        assertEquals(1L, result.get(0));
        verify(teamServiceClient).listSystemAdminUserIds();
    }

    // ---- invalidateAllUsageCaches tests ----

    @Test
    void invalidateAllUsageCaches_shouldDeleteMatchingKeys() {
        // The scan + delete pattern is used; mock the scan
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.Cursor<String> mockCursor = mock(org.springframework.data.redis.core.Cursor.class);
        when(mockCursor.hasNext()).thenReturn(true, true, false);
        when(mockCursor.next()).thenReturn("key1", "key2");
        when(redisTemplate.scan(any())).thenReturn(mockCursor);
        when(redisTemplate.delete(any(Collection.class))).thenReturn(2L);

        assertDoesNotThrow(() -> service.invalidateAllUsageCaches());

        // Called 3 times: active prefix, personal prefix, and assembled VO prefix
        verify(redisTemplate, times(3)).scan(any());
    }

    @Test
    void invalidateAllUsageCaches_shouldNotThrowOnRedisFailure() {
        when(redisTemplate.scan(any())).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> service.invalidateAllUsageCaches());
    }

    // ---- invalidateTeamCache tests ----

    @Test
    void invalidateTeamCache_shouldDeleteBothKeys() {
        service.invalidateTeamCache(10L);

        verify(redisTemplate).delete("zxyz:project:quota:team:storage:10");
        verify(redisTemplate).delete("zxyz:project:quota:team:members:10");
    }

    @Test
    void invalidateTeamCache_shouldSkipForNullTeamId() {
        service.invalidateTeamCache(null);
        verifyNoInteractions(redisTemplate);
    }

    // ---- invalidateSystemAdminCache tests ----

    @Test
    void invalidateSystemAdminCache_shouldDeleteKey() {
        service.invalidateSystemAdminCache();
        verify(redisTemplate).delete("zxyz:project:quota:system:admin:ids");
    }
}
