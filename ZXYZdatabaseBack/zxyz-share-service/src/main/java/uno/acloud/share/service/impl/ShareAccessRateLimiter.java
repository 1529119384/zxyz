package uno.acloud.share.service.impl;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;

@Component
public class ShareAccessRateLimiter {

    private static final String KEY_PREFIX = "zxyz:share:verify:";
    /** 分享验证最大尝试次数 fallback */
    private static final int FALLBACK_MAX_ATTEMPTS = 10;
    /** 分享验证限流窗口 fallback */
    private static final Duration FALLBACK_WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;
    private final ConfigGetter configGetter;
    private final int maxAttempts;
    private final Duration window;

    public ShareAccessRateLimiter(StringRedisTemplate stringRedisTemplate, ConfigGetter configGetter) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.configGetter = configGetter;
        this.maxAttempts = configGetter.getInt("app.rate-limit.share.attempts-per-window", FALLBACK_MAX_ATTEMPTS);
        this.window = Duration.ofMinutes(configGetter.getInt("app.rate-limit.share.window-minutes", (int) FALLBACK_WINDOW.toMinutes()));
    }

    public void checkAndIncrement(String shareKey, String ip) {
        String safeIp = (ip == null || ip.isBlank()) ? "unknown" : ip;
        String key = KEY_PREFIX + shareKey + ":" + safeIp;
        Long current = incrementWithTtl(key, window);
        if (current > maxAttempts) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证尝试过于频繁，请稍后再试");
        }
    }

    private Long incrementWithTtl(String key, Duration ttl) {
        String luaScript = """
            local val = redis.call('INCR', KEYS[1])
            if val == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return val
            """;
        Long result = stringRedisTemplate.execute(
            new DefaultRedisScript<>(luaScript, Long.class),
            List.of(key),
            String.valueOf(ttl.getSeconds())
        );
        return result != null ? result : 0L;
    }
}
