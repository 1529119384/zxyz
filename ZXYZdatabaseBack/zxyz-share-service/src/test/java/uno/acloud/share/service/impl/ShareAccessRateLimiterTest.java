package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareAccessRateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ConfigGetter configGetter;

    @InjectMocks
    private ShareAccessRateLimiter rateLimiter;

    private void stubConfigGetter() {
        when(configGetter.getInt(eq("app.rate-limit.share.attempts-per-window"), anyInt())).thenReturn(10);
        when(configGetter.getInt(eq("app.rate-limit.share.window-minutes"), anyInt())).thenReturn(5);
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
        stubConfigGetter();
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
        stubConfigGetter();
        stubRedisExecute(11L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimiter.checkAndIncrement("share1", "192.168.1.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void checkAndIncrement_usesUnknownWhenIpBlank() {
        stubConfigGetter();
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
        stubConfigGetter();
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
        stubConfigGetter();
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
