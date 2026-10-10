package uno.acloud.common;

import java.io.IOException;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.ClassUtils;
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
 *
 * <p>exclude 守卫（{@link ExcludeClassesPresentGuard}）：两个 FQCN 均经 Boot 4.0.8 +
 * SCA 2025.1.0.0 真实 jar 解包实测（ISSUE/51 P2-1 —— 旧值两处包名错，Boot 4 对
 * classpath 上不存在的类静默忽略，「排除」实为装饰）。若未来坐标再变而此处未跟平，
 * 测试启动即失败，不会再静默失效。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.autoconfigure.exclude="
                + "com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration,"
                + "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
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

    /**
     * exclude 守卫（ISSUE/51 P2-1）：断言 {@code spring.autoconfigure.exclude} 里写死的
     * FQCN 在当前测试 classpath 上真实存在。
     *
     * <p>为什么必须有：Boot 的 {@code AutoConfigurationImportSelector} 对「classpath 上
     * <b>不存在</b>的排除类」是<b>静默忽略</b>（只对「存在但不是自动配置类」报错）。
     * Boot 4 迁移改包名后，旧 FQCN 全部失效，exclude 沦为装饰而无人发现 —— 本守卫把
     * 「类名写错」从静默失效变成首个继承本基类的测试加载时立即失败。守卫类随测试类
     * 一起编译，maven test-compile 即验证。</p>
     */
    static final class ExcludeClassesPresentGuard {

        private ExcludeClassesPresentGuard() {
        }

        static {
            for (String fqn : new String[] {
                    "com.alibaba.cloud.nacos.discovery.NacosDiscoveryAutoConfiguration",
                    "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration"}) {
                if (!ClassUtils.isPresent(fqn, AbstractIntegrationTest.class.getClassLoader())) {
                    throw new IllegalStateException(
                            "spring.autoconfigure.exclude 的排除目标 " + fqn + " 不在测试 classpath 上："
                                    + "该值已随 Boot/SCA 版本改名（旧值会被 Boot 静默忽略 = 排除失效）。"
                                    + "请解包对应 starter jar 的 AutoConfiguration.imports 取新 FQN 并同步本基类两处"
                                    + "（@SpringBootTest properties + 本守卫）。");
                }
            }
        }
    }
}
