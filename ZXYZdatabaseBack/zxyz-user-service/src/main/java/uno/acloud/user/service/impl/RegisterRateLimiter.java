package uno.acloud.user.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

@Component
public class RegisterRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    /** 每小时每 IP 注册上限，默认 3 */
    private final int ipLimitPerHour;

    public RegisterRateLimiter(StringRedisTemplate stringRedisTemplate,
                               @Value("${app.rate-limit.register.ip-per-hour:3}") int ipLimitPerHour) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ipLimitPerHour = ipLimitPerHour;
    }

    public void checkAndIncrement(String ip) {
        String key = "zxyz:user:register:ip:" + (ip == null || ip.isBlank() ? "unknown" : ip);
        Long current = incrementWithTtl(key, Duration.ofHours(1));
        if (current > ipLimitPerHour) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "注册请求过于频繁，请稍后再试");
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
