package uno.acloud.autoconfig;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import uno.acloud.common.lock.DistributedLockTemplate;

/**
 * 自动配置 RedissonClient Bean，供需要分布式锁的服务（team-service、im-service 等）使用。
 * 各服务无需再单独定义 RedissonConfig。
 *
 * <p><b>C-10（2026-09-21）</b>：本类原位于 {@code uno.acloud.common.config} —— 一个被各服务{@code @ComponentScan} 覆盖的包（basePackages 含 {@code uno.acloud.common}），同时又登记在{@code AutoConfiguration.imports} 中 ⇒ 同一份配置被注册两次、条件在两个不同阶段各求值一次。已迁入 {@code uno.acloud.autoconfig}（不被任何 {@code @ComponentScan} 覆盖），并补上 Spring Boot 3 要求的 {@code @AutoConfiguration}。实测证据见同包 {@code AuditBufferRetryAutoConfiguration} 的类注释。</p>
 */
@AutoConfiguration(after = DataRedisAutoConfiguration.class)
@ConditionalOnClass(RedissonClient.class)
public class RedissonAutoConfiguration {

    /**
     * 分布式锁模板 Bean。
     *
     * <p>刻意在**同一个**自动配置里声明，而不是把 {@code DistributedLockTemplate} 做成
     * {@code @Component}：本 Bean 依赖 {@link #redissonClient}，挂在这里 ⇒ 存活条件与
     * {@code RedissonClient} 严格一致。否则在拿不到 {@code RedissonClient} 的服务里，
     * 组件扫描会让它**启动即失败**。</p>
     */
    @Bean
    public DistributedLockTemplate distributedLockTemplate(RedissonClient redissonClient) {
        return new DistributedLockTemplate(redissonClient);
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(DataRedisProperties redisProperties,
                                        @Value("${app.redis.redisson.pool-size:8}") int poolSize,
                                        @Value("${app.redis.redisson.min-idle-size:2}") int minIdleSize) {
        Config config = new Config();
        String scheme = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled()
                ? "rediss://" : "redis://";
        String address = scheme + redisProperties.getHost() + ":" + redisProperties.getPort();
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase());
        // 66 号 §三-6（2026-11-02）：连接池不再硬编码 8/2。高并发服务（gateway/team/im）
        // 可用 app.redis.redisson.pool-size / app.redis.redisson.min-idle-size 调整；
        // 缺省仍是 8/2，行为与历史一致。
        applyPool(singleServerConfig, poolSize, minIdleSize);
        if (redisProperties.getTimeout() != null) {
            singleServerConfig.setTimeout(Math.toIntExact(redisProperties.getTimeout().toMillis()));
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    /**
     * 应用连接池参数，带兜底与自洽校验（抽出来便于单测）：
     * poolSize≤0 → 回退 8；minIdle&lt;0 → 回退 2；minIdle&gt;poolSize → 钳到 poolSize；minIdle&lt;1 → 1。
     */
    static void applyPool(SingleServerConfig cfg, int poolSize, int minIdleSize) {
        int pool = poolSize > 0 ? poolSize : 8;
        int idle = minIdleSize >= 0 ? minIdleSize : 2;
        if (idle > pool) {
            idle = pool;
        }
        if (idle < 1) {
            idle = 1;
        }
        cfg.setConnectionPoolSize(pool).setConnectionMinimumIdleSize(idle);
    }
}
