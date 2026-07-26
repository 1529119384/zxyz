package uno.acloud.im;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Disabled("需要 Nacos + MySQL + Redis 基础设施（Docker Compose 环境）；本地开发环境跳过")
@SpringBootTest(properties = {
        "im.netty.enabled=false",
        "im.redis.subscriber.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.config.import=classpath:application-common.yml"
})
class ZxyzImApplicationTests {

    @MockitoBean
    private RedissonClient redissonClient;

    @Test
    void contextLoads() {
    }
}
