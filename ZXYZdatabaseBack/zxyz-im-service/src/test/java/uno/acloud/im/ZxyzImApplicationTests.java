package uno.acloud.im;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

@SpringBootTest(properties = {
        "im.netty.enabled=false",
        "im.redis.subscriber.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "app.internal-service-token=test-internal-token"
})
// 显式声明 test profile：各服务 application.yml 的 spring.profiles.default 已由 dev 翻为 prod
// （2026-09-13），本类不能依赖任何 profile 的默认值。datasource 占位符在上下文加载期就要能解析
// （即使 DataSource 已被 @MockitoBean 替换），故由 src/test/resources/application-test.yml 提供
// 一组「能解析但不会真去连」的默认值。
@ActiveProfiles("test")
class ZxyzImApplicationTests {

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private DataSource dataSource;

    @Test
    void contextLoads() {
    }
}
