package uno.acloud.common;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.Container;
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
        ensureTestDatabaseExists();
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

    /**
     * MySQL 容器仅初始化 {@code withDatabaseName("test")} 指定的默认库，且 {@code test}
     * 用户只对该库有授权；子类经 {@code DB_NAME} 声明的「服务独立库」既不存在也无授权，
     * 连接即被拒（CI 实测：{@code Access denied for user 'test'@'%' to database
     * 'zxyz_project'}，Flyway 取连接失败导致整个测试上下文加载失败）。
     *
     * <p>这里在容器内以 root（密码取自镜像注入的 {@code $MYSQL_ROOT_PASSWORD} 环境变量，
     * 无需硬编码）幂等建库并对 {@code test} 用户授权。{@code CREATE DATABASE IF NOT EXISTS}
     * 幂等，兼容 {@code withReuse(true)} 复用的旧容器。</p>
     */
    private static void ensureTestDatabaseExists() {
        if (DB_NAME == null || DB_NAME.isBlank() || DB_NAME.equals(mysql.getDatabaseName())) {
            return;
        }
        String sql = "CREATE DATABASE IF NOT EXISTS " + DB_NAME
                + "; GRANT ALL PRIVILEGES ON " + DB_NAME + ".* TO '"
                + mysql.getUsername() + "'@'%';";
        String command = "mysql -uroot -p\"$MYSQL_ROOT_PASSWORD\" -e \"" + sql + "\"";
        try {
            Container.ExecResult result = mysql.execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("测试数据库 " + DB_NAME + " 初始化失败（exit="
                        + result.getExitCode() + "): " + result.getStderr());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试数据库 " + DB_NAME + " 初始化被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("测试数据库 " + DB_NAME + " 初始化失败", e);
        }
    }
}
