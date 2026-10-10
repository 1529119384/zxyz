package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DuplicateKeyException;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.common.audit.OperateLog;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperateLogConsumerTest {

    @Mock
    private OperateLogMapper operateLogMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** 真实注册表：可直接读回计数器值做断言，不依赖 mock 的交互。 */
    private SimpleMeterRegistry meterRegistry;

    private OperateLogConsumer operateLogConsumer;

    @Captor
    private ArgumentCaptor<OperateLog> logCaptor;

    @Captor
    private ArgumentCaptor<String> hashCaptor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        operateLogConsumer = new OperateLogConsumer(operateLogMapper, objectMapper, meterRegistry);
    }

    // ==================== handleAuditLog — happy path ====================

    @Test
    void handleAuditLog_deserializesAndInsertsSuccessfully() throws Exception {
        // Given a valid JSON message representing an operate log
        OperateLog input = new OperateLog();
        input.setServiceName("file-service");
        input.setOperateUser(42L);
        input.setOperateTime(LocalDateTime.of(2026, 5, 27, 10, 30, 0));
        input.setClassName("uno.acloud.file.controller.FileController");
        input.setMethodName("uploadFile");
        input.setMethodParams("[projectId=1, fileName=test.txt]");
        input.setReturnValue("{\"code\":0}");
        input.setCostTime(150L);
        String json = objectMapper.writeValueAsString(input);

        operateLogConsumer.handleAuditLog(json);

        verify(operateLogMapper).insertWithHash(logCaptor.capture(), hashCaptor.capture());
        OperateLog captured = logCaptor.getValue();
        assertEquals("file-service", captured.getServiceName());
        assertEquals(42L, captured.getOperateUser());
        assertEquals("uno.acloud.file.controller.FileController", captured.getClassName());
        assertEquals("uploadFile", captured.getMethodName());
        assertEquals("[projectId=1, fileName=test.txt]", captured.getMethodParams());
        assertEquals("{\"code\":0}", captured.getReturnValue());
        assertEquals(150L, captured.getCostTime());
        // 幂等哈希应随消息写入，DB unique(message_hash) 承担去重
        String hash = hashCaptor.getValue();
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    // ==================== handleAuditLog — idempotency via DB unique key ====================

    @Test
    void handleAuditLog_duplicateKey_skipsSilentlyAsAck() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("file-service");
        input.setMethodName("uploadFile");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new DuplicateKeyException("Duplicate entry for key uk_operate_log_message_hash"))
                .when(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());

        // 命中唯一键＝重复消息，正常返回（ACK），不再抛异常、不重投
        assertDoesNotThrow(() -> operateLogConsumer.handleAuditLog(json));
        verify(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());
    }

    // ==================== B-1：幂等命中必须可观测（不能只有一行静默 WARN） ====================

    @Test
    void handleAuditLog_duplicateKey_incrementsDuplicateMetric() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("file-service");
        input.setMethodName("uploadFile");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new DuplicateKeyException("Duplicate entry for key uk_operate_log_message_hash"))
                .when(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());

        operateLogConsumer.handleAuditLog(json);
        operateLogConsumer.handleAuditLog(json);

        double duplicates = meterRegistry.get(OperateLogConsumer.METRIC_DUPLICATE_MESSAGES)
                .counter().count();
        assertEquals(2.0, duplicates,
                "每次重复投递都要计入 audit.duplicate.messages，否则重复投递链路不可观测");
    }

    @Test
    void handleAuditLog_duplicateMetricNotTouchedOnHappyPath() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("file-service");
        String json = objectMapper.writeValueAsString(input);

        operateLogConsumer.handleAuditLog(json);

        assertEquals(0.0, meterRegistry.get(OperateLogConsumer.METRIC_DUPLICATE_MESSAGES)
                        .counter().count(),
                "正常写入不得被计入重复指标");
    }

    // ==================== B-2：消费端第二道脱敏防线 ====================

    @Test
    void handleAuditLog_masksJsonPasswordBeforePersisting() throws Exception {
        // 模拟「发布端漏脱敏」：消息体里带明文密码
        String json = "{\"serviceName\":\"user-service\",\"methodName\":\"login\","
                + "\"methodParams\":\"[username=bob, password=s3cr3t]\","
                + "\"returnValue\":\"{\\\"token\\\":\\\"tok-abc\\\"}\"}";

        operateLogConsumer.handleAuditLog(json);

        verify(operateLogMapper).insertWithHash(logCaptor.capture(), anyString());
        OperateLog persisted = logCaptor.getValue();
        assertFalse(persisted.getMethodParams().contains("s3cr3t"),
                "落库的 method_params 不得含明文密码，实际：" + persisted.getMethodParams());
        assertFalse(String.valueOf(persisted.getReturnValue()).contains("tok-abc"),
                "落库的 return_value 不得含明文 token，实际：" + persisted.getReturnValue());
    }

    @Test
    void handleAuditLog_masksLombokToStringPasswordBeforePersisting() throws Exception {
        // B-13 泄露路径的真实形态：Arrays.toString(DTO) 产出 password=xxx（无引号），
        // 发布端的 JSON 正则对它完全无效 —— 消费端必须自己再挡一次。
        String json = "{\"serviceName\":\"user-service\",\"methodName\":\"trustLinkedAccount\","
                + "\"methodParams\":\"[uno.acloud.user.dto.LinkedAccountTrustRequest(password=PlainTextPwd)]\"}";

        operateLogConsumer.handleAuditLog(json);

        verify(operateLogMapper).insertWithHash(logCaptor.capture(), anyString());
        OperateLog persisted = logCaptor.getValue();
        assertFalse(persisted.getMethodParams().contains("PlainTextPwd"),
                "Lombok toString 形态的密码也必须被消费端拦住，实际：" + persisted.getMethodParams());
        assertTrue(persisted.getMethodParams().contains("password=***"));
    }

    @Test
    void handleAuditLog_keepsNonSensitiveParamsIntact() throws Exception {
        String json = "{\"serviceName\":\"file-service\",\"methodName\":\"upload\","
                + "\"methodParams\":\"[projectId=1, fileName=report.pdf]\"}";

        operateLogConsumer.handleAuditLog(json);

        verify(operateLogMapper).insertWithHash(logCaptor.capture(), anyString());
        assertEquals("[projectId=1, fileName=report.pdf]", logCaptor.getValue().getMethodParams(),
                "打码不得误伤普通参数");
    }

    // ==================== B-19：insertWithHash 返回 0 时不得静默当成功 ====================

    @Test
    void handleAuditLog_doesNotThrowButKeepsGoingWhenRowsIsZero() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("file-service");
        String json = objectMapper.writeValueAsString(input);

        when(operateLogMapper.insertWithHash(any(OperateLog.class), anyString())).thenReturn(0);

        // 返回 0 属防御性场景：不改变 ACK 语义（避免把幂等消息打进 DLQ），但必须有留痕
        assertDoesNotThrow(() -> operateLogConsumer.handleAuditLog(json));
        verify(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());
    }

    // ==================== deserialization failure (poison message) ====================

    @Test
    void handleAuditLog_invalidJson_shouldRejectToDlq() {
        String invalidJson = "{not valid json!!!";

        // Poison message: throws AmqpRejectAndDontRequeueException to route to DLQ
        AmqpRejectAndDontRequeueException ex = assertThrows(
                AmqpRejectAndDontRequeueException.class,
                () -> operateLogConsumer.handleAuditLog(invalidJson));

        assertTrue(ex.getCause() instanceof JsonProcessingException);
        verifyNoInteractions(operateLogMapper);
    }

    @Test
    void handleAuditLog_throwsRuntimeExceptionWhenJsonMissingRequiredFields() {
        // Valid JSON structure but missing fields — ObjectMapper sets them null.
        // The mapper insert may then fail on a NOT NULL constraint, but that's a DB-level check.
        String minimalJson = "{}";

        // Should not throw inside consumer — ObjectMapper happily deserializes, insert is called.
        operateLogConsumer.handleAuditLog(minimalJson);

        verify(operateLogMapper).insertWithHash(logCaptor.capture(), anyString());
        OperateLog captured = logCaptor.getValue();
        assertNull(captured.getServiceName());
        assertNull(captured.getOperateUser());
    }

    // ==================== mapper failure ====================

    @Test
    void handleAuditLog_throwsRuntimeExceptionWhenMapperFails() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("test-service");
        input.setMethodName("testMethod");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new RuntimeException("DB connection lost"))
                .when(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> operateLogConsumer.handleAuditLog(json));
        assertEquals("审计日志写入失败", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("DB connection lost"));
    }

    // ==================== re-throw ensures DLQ routing ====================

    @Test
    void handleAuditLog_reThrowsToTriggerDeadLetterQueue() throws Exception {
        // RabbitMQ routes messages to DLQ when listener throws.
        OperateLog input = new OperateLog();
        input.setServiceName("svc");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new org.apache.ibatis.exceptions.PersistenceException("non-dup constraint violation"))
                .when(operateLogMapper).insertWithHash(any(OperateLog.class), anyString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> operateLogConsumer.handleAuditLog(json));
        // 非重复键的持久化失败必须抛出以触发重投/DLQ
        assertInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class, ex.getCause());
    }

    // ==================== P0（ISSUE/51）：启动自检与降级旗标 ====================

    @Test
    void constructor_capabilityOk_flagStaysZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OperateLogConsumer(operateLogMapper, objectMapper, registry);

        assertEquals(0.0, registry.get(OperateLogConsumer.METRIC_MISSING_JSR310).gauge().value(),
                "ObjectMapper 能力齐备时降级旗标必须保持 0");
    }

    @Test
    void constructor_missingJsr310_flagRaised() {
        // 复现 Boot4 迁移时的运行时形态：无 JavaTimeModule 的裸 ObjectMapper
        ObjectMapper crippled = new ObjectMapper();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OperateLogConsumer(operateLogMapper, crippled, registry);

        assertEquals(1.0, registry.get(OperateLogConsumer.METRIC_MISSING_JSR310).gauge().value(),
                "缺 jsr310 必须置位 audit.mapper.missing.jsr310=1，让同类问题 5 分钟内在监控可见");
    }

    @Test
    void handleAuditLog_missingJsr310_rejectsToDlqWithoutInsert() {
        // 降级态：反序列化必然失败，拒绝不重投 → DLX → audit.dlq（可回放），且不得触碰 DB
        ObjectMapper crippled = new ObjectMapper();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OperateLogConsumer degraded = new OperateLogConsumer(operateLogMapper, crippled, registry);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> degraded.handleAuditLog("{\"serviceName\":\"file-service\"}"));
        verifyNoInteractions(operateLogMapper);
    }
}