package uno.acloud.user.service.impl;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.time.Duration;

@Component
public class LoginRateLimiter {

    private static final int IP_LIMIT_PER_MINUTE = 20;
    private static final int USERNAME_LIMIT_PER_MINUTE = 5;

    private final StringRedisTemplate stringRedisTemplate;

    public LoginRateLimiter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void checkAndIncrement(String ip, String username) {
        requireLimit("zxyz:user:login:ip:" + (ip == null || ip.isBlank() ? "unknown" : ip), IP_LIMIT_PER_MINUTE);
        requireLimit("zxyz:user:login:username:" + username, USERNAME_LIMIT_PER_MINUTE);
    }

    private void requireLimit(String key, int limit) {
        Long current = incrementWithTtl(key, Duration.ofMinutes(1));
        if (current > limit) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求过于频繁，请稍后再试");
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
