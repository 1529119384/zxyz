package uno.acloud.share.service.impl;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.time.Duration;

@Component
public class ShareAccessRateLimiter {

    private static final String KEY_PREFIX = "zxyz:share:verify:";
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate stringRedisTemplate;

    public ShareAccessRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void checkAndIncrement(String shareKey, String ip) {
        String safeIp = (ip == null || ip.isBlank()) ? "unknown" : ip;
        String key = KEY_PREFIX + shareKey + ":" + safeIp;
        Long current = incrementWithTtl(key, WINDOW);
        if (current > MAX_ATTEMPTS) {
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
