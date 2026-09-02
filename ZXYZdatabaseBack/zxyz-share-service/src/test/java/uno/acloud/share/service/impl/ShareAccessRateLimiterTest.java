package uno.acloud.share.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareAccessRateLimiterTest {

    /** 对应 @Value("${app.rate-limit.share.attempts-per-window:10}") 的注入值 */
    private static final int MAX_ATTEMPTS = 10;
    /** 对应 @Value("${app.rate-limit.share.window-minutes:5}") 的注入值 */
    private static final int WINDOW_MINUTES = 5;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private ShareAccessRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new ShareAccessRateLimiter(stringRedisTemplate, MAX_ATTEMPTS, WINDOW_MINUTES);
    }

    @SuppressWarnings("unchecked")
    private void stubRedisExecute(Long returnValue) {
        when(stringRedisTemplate.execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(Object[].class)
        )).thenReturn(returnValue);
    }

    @Test
    void checkAndIncrement_allowsUnderLimit() {
        stubRedisExecute(5L);

        rateLimiter.checkAndIncrement("share1", "192.168.1.1");

        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(Object[].class)
        );
    }

    @Test
    void checkAndIncrement_throwsWhenLimitExceeded() {
        stubRedisExecute(11L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimiter.checkAndIncrement("share1", "192.168.1.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void checkAndIncrement_usesUnknownWhenIpBlank() {
        stubRedisExecute(1L);

        rateLimiter.checkAndIncrement("share1", "");

        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keyCaptor.capture(),
                any(Object[].class)
        );
        String redisKey = keyCaptor.getValue().get(0);
        assertTrue(redisKey.contains("unknown"), "Redis key should contain 'unknown' for blank IP");
    }

    @Test
    void checkAndIncrement_usesUnknownWhenIpNull() {
        stubRedisExecute(1L);

        rateLimiter.checkAndIncrement("share1", null);

        ArgumentCaptor<List<String>> keyCaptor = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                keyCaptor.capture(),
                any(Object[].class)
        );
        String redisKey = keyCaptor.getValue().get(0);
        assertTrue(redisKey.contains("unknown"), "Redis key should contain 'unknown' for null IP");
    }

    @Test
    void checkAndIncrement_handlesNullRedisResult() {
        stubRedisExecute(null);

        rateLimiter.checkAndIncrement("share1", "10.0.0.1");

        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                any(List.class),
                any(Object[].class)
        );
    }

    @Test
    void reset_deletesAllKeysUnderSharePrefix() throws Exception {
        // 模拟 scan 返回该分享前缀下的多个 key（覆盖换 IP 遗留计数）
        org.springframework.data.redis.core.Cursor<byte[]> cursor = mockCursor(
                "zxyz:share:verify:share1:192.168.1.1",
                "zxyz:share:verify:share1:10.0.0.2");

        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(cursor);

        rateLimiter.reset("share1");

        verify(stringRedisTemplate).delete(List.of(
                "zxyz:share:verify:share1:192.168.1.1",
                "zxyz:share:verify:share1:10.0.0.2"));
    }

    @Test
    void reset_scansShareKeyPrefixSameAsCheckAndIncrement() throws Exception {
        org.springframework.data.redis.core.Cursor<byte[]> cursor = mockCursor("zxyz:share:verify:share1:unknown");
        when(stringRedisTemplate.execute(any(org.springframework.data.redis.core.RedisCallback.class)))
                .thenReturn(cursor);

        rateLimiter.reset("share1");

        verify(stringRedisTemplate).delete(java.util.List.of("zxyz:share:verify:share1:unknown"));
    }

    @SuppressWarnings("unchecked")
    private org.springframework.data.redis.core.Cursor<byte[]> mockCursor(String... keys) {
        org.springframework.data.redis.core.Cursor<byte[]> cursor =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.Cursor.class);
        java.util.List<byte[]> remaining = new java.util.ArrayList<>();
        for (String k : keys) {
            remaining.add(k.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        java.util.Iterator<byte[]> it = remaining.iterator();
        org.mockito.Mockito.when(cursor.hasNext()).thenAnswer(invocation -> it.hasNext());
        org.mockito.Mockito.when(cursor.next()).thenAnswer(invocation -> it.next());
        return cursor;
    }
}
