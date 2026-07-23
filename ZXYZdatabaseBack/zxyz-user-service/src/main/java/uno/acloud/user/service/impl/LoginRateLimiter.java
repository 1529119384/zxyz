package uno.acloud.user.service.impl;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;

import java.time.Duration;

@Component
public class LoginRateLimiter {

    /** 每分钟每 IP 登录上限 fallback */
    private static final int FALLBACK_IP_LIMIT_PER_MINUTE = 20;
    /** 每分钟每用户名登录上限 fallback */
    private static final int FALLBACK_USERNAME_LIMIT_PER_MINUTE = 5;

    private final StringRedisTemplate stringRedisTemplate;
    private final ConfigGetter configGetter;
    private final int ipLimitPerMinute;
    private final int usernameLimitPerMinute;

    public LoginRateLimiter(StringRedisTemplate stringRedisTemplate, ConfigGetter configGetter) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.configGetter = configGetter;
        this.ipLimitPerMinute = configGetter.getInt("app.rate-limit.login.ip-per-minute", FALLBACK_IP_LIMIT_PER_MINUTE);
        this.usernameLimitPerMinute = configGetter.getInt("app.rate-limit.login.username-per-minute", FALLBACK_USERNAME_LIMIT_PER_MINUTE);
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
