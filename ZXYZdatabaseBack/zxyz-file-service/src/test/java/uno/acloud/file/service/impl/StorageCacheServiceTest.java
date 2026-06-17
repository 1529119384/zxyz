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
}
