package uno.acloud.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.audit.mq.OperateLogConsumer;
import uno.acloud.audit.service.AuditLogCleanupService;
import uno.acloud.common.audit.AbstractLogAspect;
import uno.acloud.common.audit.AuditBufferRetryScheduler;
import uno.acloud.common.audit.OperateLog;
import uno.acloud.common.audit.SensitiveDataMasker;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * audit-service 全上下文冒烟（54 号审计 A2 的收口）。
 *
 * <p><b>为什么必须有</b>：本服务是全仓唯一零集成测试的模块（无 {@code @SpringBootTest}、
 * 无 {@code AbstractIntegrationTest} 子类），却恰是唯一丢过 17 小时审计数据的服务 ——
 * @Insert SQL / DLQ 双参签名 / Flyway 唯一约束此前从未在可运行上下文里装配过。
 * 本测试把整个上下文装配一次 + 真实持久化路径走通一次，让「装配面」从
 * 「code review 时有人记得」变成「测试加载即验证」。</p>
 *
 * <p><b>离线策略</b>（Docker 未启动 / CI 无中间件也能跑）：
 * <ul>
 *   <li>{@code webEnvironment=NONE}：audit 无 HTTP 端点面（纯 MQ 消费者）；</li>
 *   <li>数据源 = 内存 H2（MODE=MySQL）+ 幂等建表脚本（{@code sql/h2/audit_schema_h2.sql}）；</li>
 *   <li>{@code RabbitTemplate} 用 {@code @MockitoBean} 替换，listener 容器
 *       {@code auto-startup=false}（两个 @RabbitListener 容器 bean 仍被注册并装配——
 *       这正是要冒烟的面——但绝不连接 broker）；</li>
 *   <li>Redis 指向死端口 6399：任何「上下文加载期真实去连 Redis」的回归立刻红
 *       （参照 im-service ZxyzImApplicationTests 的假绿防回归设计）。</li>
 * </ul></p>
 *
 * <p><b>bean 装配面清单</b>（本测试加载即验证）：</p>
 * <ul>
 *   <li>audit 本体：{@link ZxyzAuditServiceApplication}（@EnableScheduling）、
 *       {@link OperateLogConsumer}（@RabbitListener 主队列）、AuditDlqConsumer（@RabbitListener DLQ）、
 *       {@link AuditLogCleanupService}（@Scheduled 清理）、RabbitMqConfig（交换机/队列/绑定 4 bean）、
 *       {@link OperateLogMapper}（MyBatis @Mapper）；</li>
 *   <li>common 经 @ComponentScan("uno.acloud.common")：{@link SensitiveDataMasker}
 *       （消费端第二道脱敏防线依赖）、{@link AuditEventPublisher}（依赖被 mock 的 RabbitTemplate）、
 *       GlobalExceptionHandler / WebMvcAutoConfig / MybatisPlusConfig / InternalServiceAuthInterceptor、
 *       {@link AbstractLogAspect} 子类（若有）与 {@link AuditBufferRetryScheduler}（@Scheduled）；</li>
 *   <li>common 自动配置（AutoConfiguration.imports）：MqRecovererAutoConfiguration（全局
 *       MessageRecoverer）、AuthServicePortAutoConfig（AuthServicePort）、PermissionCacheAutoConfiguration
 *       （PermissionCache + 失效监听容器 bean）、RedissonAutoConfiguration（RedissonClient——
 *       由下方 @MockitoBean 替换）、CacheConfig（@EnableCaching + RedisCacheManager）、
 *       {@link AuditBufferRetryScheduler} 所在的 AuditBufferRetryAutoConfiguration、
 *       RemoteStpInterfaceAutoConfig（RestClient + RemoteStpInterfaceImpl）、
 *       JasyptPropertiesEncryptorAutoConfiguration；</li>
 *   <li>starter：RestClientAutoConfiguration（RestClient / RestClient.Builder）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        // 离线三件套：关 Nacos 注册/配置（config 侧再由影子 application.yml 收敛 import +
        // 此处双保险），listener 容器绝不连 broker
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 韧性重试/熔断的 30s 级等待只拖慢失败路径，与本冒烟无关，收紧加速反馈
        "resilience4j.retry.instances.serviceClient.wait-duration=1ms",
        "resilience4j.circuitbreaker.instances.serviceClient.wait-duration-in-open-state=1ms"
})
@ActiveProfiles("test")
class AuditContextSmokeTest {

    /** RabbitTemplate：AuditEventPublisher（common 扫描件）的构造依赖，mock 掉即离线 */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /** RedissonAutoConfiguration 的 RedissonClient bean：构造即建池，必须替换才能离线 */
    @MockitoBean
    private org.redisson.api.RedissonClient redissonClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OperateLogConsumer operateLogConsumer;

    @Autowired
    private OperateLogMapper operateLogMapper;

    @Autowired
    private DataSource dataSource;

    // ==================== 全上下文装配 ====================

    @Test
    void contextLoads() {
        // 上下文加载成功即本测试的第一断言；显式点名关键 bean 让「装配面」可读
        assertThat(operateLogConsumer).isNotNull();
        assertThat(operateLogMapper).isNotNull();
        assertThat(objectMapper).isNotNull();
    }

    // ==================== jsr310 探针（51 号 P0 的直接回归钉） ====================

    /**
     * 用**注入的**（即 Boot 自动配置、运行时 classpath 上的）ObjectMapper 反序列化
     * 含 LocalDateTime 的样例 —— 命中 51 号 P0 的生产故障路径（jsr310 仅 test scope 时
     * readValue(OperateLog) 全量抛 InvalidDefinitionException，审计静默丢失 17h）。
     * OperateLogConsumer 构造器自检只把旗标打给监控，这里是测试侧的独立二次确认。
     */
    @Test
    void injectedObjectMapper_deserializesLocalDateTime_issue51RegressionNail() throws Exception {
        String json = "{\"serviceName\":\"file-service\",\"operateUser\":42,"
                + "\"operateTime\":\"2026-10-11T12:34:56\",\"methodName\":\"upload\","
                + "\"costTime\":150}";

        OperateLog log = objectMapper.readValue(json, OperateLog.class);

        assertThat(log.getOperateTime())
                .as("注入的 ObjectMapper 必须能反序列化 LocalDateTime（运行时 jsr310 在位）——"
                        + "51 号 P0（审计静默丢失 17h）的直接回归钉")
                .isEqualTo(LocalDateTime.of(2026, 10, 11, 12, 34, 56));
    }

    // ==================== 消费入口 → H2 持久化路径 ====================

    /**
     * mock 消息注入 {@link OperateLogConsumer#handleAuditLog(String)}，走通
     * 「反序列化 → 脱敏 → messageHash 计算 → insertWithHash → H2 落库」全链，
     * 并从 H2 读回验证：@Insert 显式列清单、唯一约束全部真实生效。
     */
    @Test
    void consumerMessage_persistsThroughRealMapperToH2() throws Exception {
        String json = "{\"serviceName\":\"smoke-service\",\"operateUser\":7,"
                + "\"operateTime\":\"2026-10-11T08:00:00\","
                + "\"className\":\"uno.acloud.smoke.DemoController\","
                + "\"methodName\":\"doSmoke\","
                + "\"methodParams\":\"[projectId=1, password=PlainPwd]\","
                + "\"returnValue\":\"{\\\"code\\\":0}\",\"costTime\":88}";

        operateLogConsumer.handleAuditLog(json);

        String expectedHash = sha256Hex(json);
        // 消费端第二道脱敏必须生效（SensitiveDataMasker bean 被真实装配并参与链路）
        Map<String, Object> row = queryOneRow(
                "SELECT SERVICE_NAME, METHOD_NAME, OPERATE_TIME, METHOD_PARAMS, MESSAGE_HASH "
                        + "FROM operate_log WHERE MESSAGE_HASH = ?",
                expectedHash);
        assertThat(row).as("消息必须真实落库（H2）").isNotEmpty();
        assertThat(row.get("SERVICE_NAME")).isEqualTo("smoke-service");
        assertThat(row.get("METHOD_NAME")).isEqualTo("doSmoke");
        assertThat(row.get("OPERATE_TIME")).isNotNull();
        assertThat(String.valueOf(row.get("METHOD_PARAMS")))
                .as("落库前必须完成敏感字段打码（消费端防线，SensitiveDataMasker 参与链路）")
                .doesNotContain("PlainPwd");
        assertThat(String.valueOf(row.get("MESSAGE_HASH"))).isEqualTo(expectedHash);

        // 重复投递同一消息：消费入口必须按幂等 ACK 跳过，且库里只有一行
        // （DB 唯一约束 uk_operate_log_message_hash 在可运行上下文中的直接验证）
        operateLogConsumer.handleAuditLog(json);
        assertThat(countRowsByHash(json))
                .as("重复消息命中唯一约束后必须跳过（ACK），且不得产生第二行")
                .isEqualTo(1);
    }

    /**
     * 54 号 🔴-1 的实体侧锚点：公共 {@code insert()}（MyBatis-Plus 按实体字段生成列清单）
     * 在实体补齐 messageHash 后能把 message_hash 一并写入 —— 实体-DDL 对齐的运行时验证，
     * 与 OperateLogEntityDdlGuardTest 的静态守护互补。
     */
    @Test
    void publicBaseMapperInsert_writesMessageHashColumn() throws Exception {
        String hash = "a".repeat(64);
        OperateLog entity = new OperateLog();
        entity.setServiceName("smoke-service");
        entity.setMethodName("publicInsertProbe");
        entity.setOperateTime(LocalDateTime.of(2026, 10, 11, 9, 30));
        entity.setMessageHash(hash);

        int inserted = operateLogMapper.insert(entity);

        assertThat(inserted).isEqualTo(1);
        Map<String, Object> row = queryOneRow(
                "SELECT MESSAGE_HASH FROM operate_log WHERE SERVICE_NAME = ? AND METHOD_NAME = ?",
                "smoke-service", "publicInsertProbe");
        assertThat(row).as("公共 insert() 必须能把 message_hash 一并写入（实体补字段后的语义收敛）")
                .isNotEmpty();
        assertThat(String.valueOf(row.get("MESSAGE_HASH"))).isEqualTo(hash);
    }

    // ==================== H2 工具 ====================

    private Map<String, Object> queryOneRow(String sql, Object... params) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i).toUpperCase(), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private int countRowsByHash(String json) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM operate_log WHERE MESSAGE_HASH = ?")) {
            ps.setString(1, sha256Hex(json));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
