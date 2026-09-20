package uno.acloud.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;
import uno.acloud.common.lock.DistributedLockTemplate;

/**
 * 自动配置 RedissonClient Bean，供需要分布式锁的服务（team-service、im-service 等）使用。
 * 各服务无需再单独定义 RedissonConfig。
 */
@AutoConfiguration
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
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        String scheme = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled()
                ? "rediss://" : "redis://";
        String address = scheme + redisProperties.getHost() + ":" + redisProperties.getPort();
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase())
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(2);
        if (redisProperties.getTimeout() != null) {
            singleServerConfig.setTimeout(Math.toIntExact(redisProperties.getTimeout().toMillis()));
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }
}
