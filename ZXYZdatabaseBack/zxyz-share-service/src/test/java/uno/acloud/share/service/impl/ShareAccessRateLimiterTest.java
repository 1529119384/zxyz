package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShareAccessRateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private ShareAccessRateLimiter rateLimiter;

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
}
