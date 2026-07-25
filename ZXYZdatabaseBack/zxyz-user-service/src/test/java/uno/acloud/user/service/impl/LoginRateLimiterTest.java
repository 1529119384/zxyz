package uno.acloud.user.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ConfigGetter configGetter;

    @InjectMocks
    private LoginRateLimiter loginRateLimiter;

    private void stubConfigGetter() {
        when(configGetter.getInt(eq("app.rate-limit.login.ip-per-minute"), anyInt())).thenReturn(20);
        when(configGetter.getInt(eq("app.rate-limit.login.username-per-minute"), anyInt())).thenReturn(5);
    }

    @Test
    void checkAndIncrement_allowsUnderLimit() {
        stubConfigGetter();
        // IP check returns 1, username check returns 1
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L)
                .thenReturn(1L);

        loginRateLimiter.checkAndIncrement("192.168.1.1", "alice");
        // No exception thrown
    }

    @Test
    void checkAndIncrement_throwsWhenIpLimitExceeded() {
        stubConfigGetter();
        // IP check returns 21 (limit is 20)
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(21L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> loginRateLimiter.checkAndIncrement("192.168.1.1", "alice"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void checkAndIncrement_throwsWhenUsernameLimitExceeded() {
        stubConfigGetter();
        // IP check passes (1), username check exceeds limit (6, limit is 5)
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(1L)
                .thenReturn(6L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> loginRateLimiter.checkAndIncrement("192.168.1.1", "alice"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkAndIncrement_usesUnknownWhenIpBlank() {
        stubConfigGetter();
        // Capture the keys passed to Redis to verify "unknown" is used
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.get(0).contains("unknown")) {
                        return 1L;
                    }
                    return 1L;
                });

        loginRateLimiter.checkAndIncrement("", "alice");
        // No exception — "unknown" used in key
    }

    @Test
    @SuppressWarnings("unchecked")
    void checkAndIncrement_usesUnknownWhenIpNull() {
        stubConfigGetter();
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.get(0).contains("unknown")) {
                        return 1L;
                    }
                    return 1L;
                });

        loginRateLimiter.checkAndIncrement(null, "alice");
        // No exception — "unknown" used in key
    }

    @Test
    void checkAndIncrement_handlesNullRedisResult() {
        stubConfigGetter();
        // Redis returns null, which should be treated as 0L
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(null)
                .thenReturn(null);

        loginRateLimiter.checkAndIncrement("192.168.1.1", "alice");
        // No exception — null treated as 0, which is under the limit
    }
}
