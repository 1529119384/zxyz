package uno.acloud.user.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 与 {@link LoginRateLimiterTest} 同款 mock 风格：StringRedisTemplate mock +
 * Lua execute 桩返回值；锁定参数走 {@link ServiceProperties}（@ConfigurationProperties），
 * 测试里直接 set 实际默认值。
 */
@ExtendWith(MockitoExtension.class)
class LoginFailureLockoutServiceTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final int FAIL_WINDOW_MINUTES = 15;
    private static final int LOCKOUT_MINUTES = 30;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ServiceProperties serviceProperties;
    private LoginFailureLockoutService lockoutService;

    @BeforeEach
    void setUp() {
        serviceProperties = new ServiceProperties();
        ServiceProperties.LoginLockout lockout = serviceProperties.getSecurity().getLoginLockout();
        lockout.setEnabled(true);
        lockout.setMaxAttempts(MAX_ATTEMPTS);
        lockout.setFailWindowMinutes(FAIL_WINDOW_MINUTES);
        lockout.setLockoutMinutes(LOCKOUT_MINUTES);
        lockoutService = new LoginFailureLockoutService(stringRedisTemplate, serviceProperties);
    }

    private void stubIncrReturning(long attempts) {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(attempts);
    }

    // ---- checkLocked ----

    @Test
    void checkLocked_rejectsWithLoginLockedWhenLockKeyExists() {
        when(stringRedisTemplate.hasKey(LoginFailureLockoutService.LOCK_KEY_PREFIX + "alice")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> lockoutService.checkLocked("alice"));

        assertEquals(UserErrorCode.LOGIN_LOCKED.getCode(), ex.getErrorCode());
    }

    @Test
    void checkLocked_allowsWhenNotLocked() {
        when(stringRedisTemplate.hasKey(LoginFailureLockoutService.LOCK_KEY_PREFIX + "alice")).thenReturn(false);

        assertDoesNotThrow(() -> lockoutService.checkLocked("alice"));
    }

    @Test
    void checkLocked_noopWhenDisabled() {
        serviceProperties.getSecurity().getLoginLockout().setEnabled(false);

        assertDoesNotThrow(() -> lockoutService.checkLocked("alice"));

        verifyNoInteractions(stringRedisTemplate);
    }

    // ---- recordFailure：累计 → 达阈值锁定 ----

    @Test
    void recordFailure_incrementsAndDoesNotLockBeforeThreshold() {
        stubIncrReturning(MAX_ATTEMPTS - 1);

        lockoutService.recordFailure("alice");

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class),
                eq(List.of(LoginFailureLockoutService.FAIL_KEY_PREFIX + "alice")),
                eq(String.valueOf(Duration.ofMinutes(FAIL_WINDOW_MINUTES).getSeconds())));
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    void recordFailure_setsLockWhenThresholdReached() {
        stubIncrReturning(MAX_ATTEMPTS);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        lockoutService.recordFailure("alice");

        verify(valueOperations).setIfAbsent(
                eq(LoginFailureLockoutService.LOCK_KEY_PREFIX + "alice"), eq("1"),
                eq(Duration.ofMinutes(LOCKOUT_MINUTES)));
    }

    @Test
    void recordFailure_usesFailWindowTtl() {
        stubIncrReturning(1L);

        lockoutService.recordFailure("alice");

        verify(stringRedisTemplate).execute(any(DefaultRedisScript.class), anyList(),
                eq(String.valueOf(Duration.ofMinutes(FAIL_WINDOW_MINUTES).getSeconds())));
    }

    // ---- recordSuccess ----

    @Test
    void recordSuccess_deletesFailCounter() {
        lockoutService.recordSuccess("alice");

        verify(stringRedisTemplate).delete(LoginFailureLockoutService.FAIL_KEY_PREFIX + "alice");
    }

    @Test
    void recordSuccess_noopWhenDisabled() {
        serviceProperties.getSecurity().getLoginLockout().setEnabled(false);

        lockoutService.recordSuccess("alice");

        verifyNoInteractions(stringRedisTemplate);
    }

    // ---- recordFailure disabled ----

    @Test
    void recordFailure_noopWhenDisabled() {
        serviceProperties.getSecurity().getLoginLockout().setEnabled(false);

        lockoutService.recordFailure("alice");

        verifyNoInteractions(stringRedisTemplate);
    }

    // ---- 防御性：Redis 返回 null 按累计 0 处理（不锁定） ----

    @Test
    void recordFailure_treatsNullRedisResultAsZero() {
        when(stringRedisTemplate.execute(any(DefaultRedisScript.class), anyList(), anyString()))
                .thenReturn(null);

        assertDoesNotThrow(() -> lockoutService.recordFailure("alice"));

        verify(stringRedisTemplate, never()).opsForValue();
    }
}
