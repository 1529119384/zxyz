package uno.acloud.share.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

@Component
public class ShareAccessRateLimiter {

    private static final String KEY_PREFIX = "zxyz:share:verify:";

    private final StringRedisTemplate stringRedisTemplate;
    /** 分享验证最大尝试次数，默认 10 */
    private final int maxAttempts;
    /** 分享验证限流窗口，默认 5 分钟 */
    private final Duration window;

    public ShareAccessRateLimiter(StringRedisTemplate stringRedisTemplate,
                                  @Value("${app.rate-limit.share.attempts-per-window:10}") int maxAttempts,
                                  @Value("${app.rate-limit.share.window-minutes:5}") int windowMinutes) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofMinutes(windowMinutes);
    }

    public void checkAndIncrement(String shareKey, String ip) {
        String safeIp = (ip == null || ip.isBlank()) ? "unknown" : ip;
        String key = KEY_PREFIX + shareKey + ":" + safeIp;
        Long current = incrementWithTtl(key, window);
        if (current > maxAttempts) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证尝试过于频繁，请稍后再试");
        }
    }

    /**
     * 校验成功后重置该分享的验证次数计数器，避免成功校验也被计入「失败尝试」导致误锁。
     * 删除该分享前缀下的全部 key（覆盖用户换 IP/换设备后遗留的计数），key 格式与
     * {@link #checkAndIncrement} 一致。
     */
    public void reset(String shareKey) {
        String prefix = KEY_PREFIX + shareKey + ":";
        List<String> keys = new java.util.ArrayList<>();
        try (Cursor<byte[]> cursor = stringRedisTemplate.execute(
                (org.springframework.data.redis.core.RedisCallback<Cursor<byte[]>>)
                        (connection) -> connection.scan(ScanOptions.scanOptions().match(prefix + "*").count(100).build()))) {
            while (cursor != null && cursor.hasNext()) {
                keys.add(new String(cursor.next(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // 尽力而为：scan 失败不回滚校验成功，仅跳过清理
            return;
        }
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
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
