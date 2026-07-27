package uno.acloud.common;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试基类 — 提供 MySQL + Redis Testcontainers 容器。
 *
 * <p>子类通过 {@code static { DB_NAME = "xxx"; }} 设置数据库名，
 * 并用 {@code @MockitoBean} mock 外部服务客户端。</p>
 *
 * <p>需要 Docker Desktop 运行。容器启用 {@code withReuse(true)} 跨测试复用。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.autoconfigure.exclude="
                + "com.alibaba.cloud.nacos.registry.NacosDiscoveryAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.flyway.enabled=true"
})
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    protected static String DB_NAME;

    static {
        mysql.start();
        redis.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String jdbcUrl = mysql.getJdbcUrl().replace("/test", "/" + DB_NAME);
        String fullUrl = jdbcUrl
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
        registry.add("spring.datasource.url", () -> fullUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("config.datasource.jdbc-url", () -> fullUrl);
        registry.add("config.datasource.username", mysql::getUsername);
        registry.add("config.datasource.password", mysql::getPassword);
        registry.add("config.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
