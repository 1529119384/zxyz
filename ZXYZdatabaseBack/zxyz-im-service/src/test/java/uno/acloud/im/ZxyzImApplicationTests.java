package uno.acloud.im;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

@SpringBootTest(properties = {
        "im.netty.enabled=false",
        "im.redis.subscriber.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "app.internal-service-token=test-internal-token"
})
class ZxyzImApplicationTests {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private DataSource dataSource;

    @Test
    void contextLoads() {
    }
}
