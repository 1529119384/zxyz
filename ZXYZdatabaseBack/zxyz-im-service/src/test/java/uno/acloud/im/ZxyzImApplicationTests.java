package uno.acloud.im;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

@SpringBootTest(properties = {
        "im.netty.enabled=false",
        // 🔴 真实的开关名是 app.im.cluster.enabled（见 ImClusterSubscriber:30）。
        // 这里原先写的是 im.redis.subscriber.enabled —— 一个**全仓不存在的属性**，
        // 写了一个「看起来在关、实际什么都没关」的开关。已订正为真名。
        "app.im.cluster.enabled=false",
        // 🔴 把 Redis 指到一个必然无人监听的端口：本类只做「上下文能否加载」的冒烟，
        // 不该依赖任何外部中间件。此前它**隐式依赖「本机恰好跑着 Redis」**——
        // CI runner 没有 Redis ⇒ 红；本机 6379 有 Redis ⇒ 绿。属典型「本地绿 / CI 红」假绿。
        // 钉死一个死端口后，任何「上下文加载期真的去连 Redis」的回归都会在本地立刻现形。
        "spring.data.redis.port=6399",
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
