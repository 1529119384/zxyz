package uno.acloud.email.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.exception.BusinessException;

import java.util.Collections;

@Component
public class EmailRateLimiter {

    /**
     * Lua 脚本：原子执行 INCR + EXPIRE，返回递增后的计数值。
     * KEYS[1] = 限流 key, ARGV[1] = 过期秒数
     */
    private static final String INCR_EXPIRE_SCRIPT =
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return current";

    private final StringRedisTemplate stringRedisTemplate;
    private final EmailProperties emailProperties;
    private final DefaultRedisScript<Long> rateLimitScript;

    public EmailRateLimiter(StringRedisTemplate stringRedisTemplate, EmailProperties emailProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.emailProperties = emailProperties;
        this.rateLimitScript = new DefaultRedisScript<>(INCR_EXPIRE_SCRIPT, Long.class);
    }

    public void requireVerifyCodeAllowed(String email, String ip) {
        requireLimit("zxyz:email:verify:ip:" + (ip == null || ip.isBlank() ? "unknown" : ip), emailProperties.getIpLimitPerMinute());
        requireLimit("zxyz:email:verify:address:" + email, emailProperties.getEmailLimitPerMinute());
    }

    private void requireLimit(String key, int limit) {
        Long current = stringRedisTemplate.execute(rateLimitScript, Collections.singletonList(key), "60");
        if (current != null && current > limit) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请求过于频繁，请稍后再试");
        }
    }
}
