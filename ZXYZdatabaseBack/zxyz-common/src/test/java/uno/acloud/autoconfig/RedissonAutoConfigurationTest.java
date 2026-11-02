package uno.acloud.autoconfig;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 66 号 §三-6（2026-11-02）：Redisson 连接池参数可配置 + 兜底/钳制规则。
 * 此前是硬编码 8/2（不可配），本测试钉住「配置值生效、异常值有确定兜底」。
 */
class RedissonAutoConfigurationTest {

    private SingleServerConfig cfg() {
        return new Config().useSingleServer().setAddress("redis://127.0.0.1:6379");
    }

    @Test
    void applyPool_usesConfiguredValues() {
        SingleServerConfig c = cfg();
        RedissonAutoConfiguration.applyPool(c, 16, 4);
        assertEquals(16, c.getConnectionPoolSize(), "pool-size 应取配置值");
        assertEquals(4, c.getConnectionMinimumIdleSize(), "min-idle-size 应取配置值");
    }

    @Test
    void applyPool_defaultsWhenInvalid() {
        SingleServerConfig c = cfg();
        RedissonAutoConfiguration.applyPool(c, 0, -1);
        assertEquals(8, c.getConnectionPoolSize(), "poolSize<=0 应回退 8");
        assertEquals(2, c.getConnectionMinimumIdleSize(), "minIdle<0 应回退 2");
    }

    @Test
    void applyPool_clampsIdleToPoolAndAtLeastOne() {
        SingleServerConfig c1 = cfg();
        RedissonAutoConfiguration.applyPool(c1, 3, 99);
        assertEquals(3, c1.getConnectionPoolSize());
        assertEquals(3, c1.getConnectionMinimumIdleSize(), "minIdle>pool 应钳到 pool");

        SingleServerConfig c2 = cfg();
        RedissonAutoConfiguration.applyPool(c2, 5, 0);
        assertEquals(1, c2.getConnectionMinimumIdleSize(), "minIdle<1 应钳到 1");
    }
}
