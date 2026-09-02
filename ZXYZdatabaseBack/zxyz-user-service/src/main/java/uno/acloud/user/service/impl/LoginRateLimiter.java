package uno.acloud.user.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.time.Duration;

@Component
public class LoginRateLimiter {

    private final StringRedisTemplate stringRedisTemplate;
    /** 每分钟每 IP 登录上限，默认 20 */
    private final int ipLimitPerMinute;
    /** 每分钟每用户名登录上限，默认 5 */
    private final int usernameLimitPerMinute;

    public LoginRateLimiter(StringRedisTemplate stringRedisTemplate,
                            @Value("${app.rate-limit.login.ip-per-minute:20}") int ipLimitPerMinute,
                            @Value("${app.rate-limit.login.username-per-minute:5}") int usernameLimitPerMinute) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ipLimitPerMinute = ipLimitPerMinute;
        this.usernameLimitPerMinute = usernameLimitPerMinute;
    }

    public void checkAndIncrement(String ip, String username) {
        requireLimit("zxyz:user:login:ip:" + (ip == null || ip.isBlank() ? "unknown" : ip), ipLimitPerMinute);
        requireLimit("zxyz:user:login:username:" + username, usernameLimitPerMinute);
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
