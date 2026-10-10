package uno.acloud.user.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import uno.acloud.common.UserErrorCode;
import uno.acloud.common.util.LogMaskingUtil;
import uno.acloud.exception.BusinessException;
import uno.acloud.user.config.ServiceProperties;

import java.time.Duration;
import java.util.List;

/**
 * 账号维度登录失败锁定（P3-2 最小补强，撞库防护）。
 *
 * <p>与 {@link LoginRateLimiter} 的频控互补：频控限「请求频率」（IP/用户名每分钟次数），
 * 本组件限「累计失败」——慢速撞库可以把每次尝试都压在频控阈值以下绕过频控，
 * 但认证失败会被持续累积并最终触发锁定。</p>
 *
 * <p>Redis 结构（user-service 业务库 {@code REDIS_DATABASE}，默认 db4）：
 * <ul>
 *   <li>失败计数 {@code zxyz:auth:fail:<username>}：Lua 原子 INCR + 首次写入设 TTL
 *       （窗口语义 = 自该账号第一次失败起算 {@code fail-window-minutes} 分钟，窗口结束计数自然清零）；</li>
 *   <li>锁定标记 {@code zxyz:auth:lock:<username>}：达到阈值时 SET NX EX 写入，
 *       TTL = {@code lockout-minutes}；锁定期间登录在密码校验前即被拒绝
 *       （{@link UserErrorCode#LOGIN_LOCKED}，HTTP 429）。</li>
 * </ul>
 * 登录成功删除失败计数；锁定标记不主动删（锁定期内不允许靠「蒙对密码」提前解除），
 * 按自身 TTL 自然过期。</p>
 *
 * <p>参数（{@code app.security.login-lockout.*}：enabled / max-attempts / fail-window-minutes /
 * lockout-minutes）绑定在 {@link ServiceProperties}（即 {@code @ConfigurationProperties(prefix="app")}），
 * 每次调用时实时读取——Nacos 刷新经 ConfigurationPropertiesRebinder 重绑后立即生效。
 * 已知取舍：按用户名维度锁定意味着攻击者可蓄意锁他人账号（可用性换安全），
 * 锁定时长 30 分钟有限，且频控（IP 维度 20/min）已限制单 IP 制造锁定的速度。</p>
 */
@Slf4j
@Component
public class LoginFailureLockoutService {

    static final String FAIL_KEY_PREFIX = "zxyz:auth:fail:";
    static final String LOCK_KEY_PREFIX = "zxyz:auth:lock:";

    /** INCR + 首次写入 EXPIRE，与 LoginRateLimiter 同款原子脚本（避免 INCR 与 EXPIRE 之间崩溃留下永久计数）。 */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>("""
            local val = redis.call('INCR', KEYS[1])
            if val == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return val
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ServiceProperties serviceProperties;

    public LoginFailureLockoutService(StringRedisTemplate stringRedisTemplate,
                                      ServiceProperties serviceProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.serviceProperties = serviceProperties;
    }

    /**
     * 登录入口在密码校验前调用：锁定期间直接拒绝，不泄露「密码是否正确」。
     */
    public void checkLocked(String username) {
        if (!enabled()) {
            return;
        }
        Boolean locked = stringRedisTemplate.hasKey(LOCK_KEY_PREFIX + username);
        if (Boolean.TRUE.equals(locked)) {
            log.warn("账号 {} 登录被拒绝：处于失败锁定窗口内", LogMaskingUtil.maskUsername(username));
            throw new BusinessException(UserErrorCode.LOGIN_LOCKED, "失败次数过多，账号已临时锁定，请稍后再试");
        }
    }

    /**
     * 认证失败路径调用：累计失败次数，达到阈值即写入锁定标记。
     * 用户名不存在的尝试同样计数（防用户名枚举）。
     */
    public void recordFailure(String username) {
        if (!enabled()) {
            return;
        }
        ServiceProperties.LoginLockout config = config();
        Long attempts = stringRedisTemplate.execute(
                INCR_WITH_TTL_SCRIPT,
                List.of(FAIL_KEY_PREFIX + username),
                String.valueOf(Duration.ofMinutes(config.getFailWindowMinutes()).getSeconds())
        );
        long current = attempts != null ? attempts : 0L;
        if (current >= config.getMaxAttempts()) {
            Boolean firstLock = stringRedisTemplate.opsForValue().setIfAbsent(
                    LOCK_KEY_PREFIX + username, "1", Duration.ofMinutes(config.getLockoutMinutes()));
            if (Boolean.TRUE.equals(firstLock)) {
                log.warn("账号 {} 在失败窗口内累计 {} 次失败达到阈值 {}，锁定 {} 分钟",
                        LogMaskingUtil.maskUsername(username), current, config.getMaxAttempts(), config.getLockoutMinutes());
            }
        }
    }

    /**
     * 登录成功路径调用：删除失败计数，从零重新累计。
     */
    public void recordSuccess(String username) {
        if (!enabled()) {
            return;
        }
        stringRedisTemplate.delete(FAIL_KEY_PREFIX + username);
    }

    private boolean enabled() {
        return config().isEnabled();
    }

    private ServiceProperties.LoginLockout config() {
        return serviceProperties.getSecurity().getLoginLockout();
    }
}
