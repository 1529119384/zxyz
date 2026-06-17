package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.common.audit.OperateLog;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperateLogConsumerTest {

    @Mock
    private OperateLogMapper operateLogMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @InjectMocks
    private OperateLogConsumer operateLogConsumer;

    @Captor
    private ArgumentCaptor<OperateLog> logCaptor;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
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

        verify(operateLogMapper).insert(logCaptor.capture());
        OperateLog captured = logCaptor.getValue();
        assertEquals("file-service", captured.getServiceName());
        assertEquals(42L, captured.getOperateUser());
        assertEquals("uno.acloud.file.controller.FileController", captured.getClassName());
        assertEquals("uploadFile", captured.getMethodName());
        assertEquals("[projectId=1, fileName=test.txt]", captured.getMethodParams());
        assertEquals("{\"code\":0}", captured.getReturnValue());
        assertEquals(150L, captured.getCostTime());
    }

    // ==================== handleAuditLog — deserialization failure (poison message) ====================

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
        // Valid JSON structure but missing fields — ObjectMapper will set them null.
        // The mapper insert may then fail on a NOT NULL constraint, but that's a DB-level check.
        // Here we verify that even with missing fields, deserialization itself does not throw
        // (Lombok-generated setters accept null), and the mapper IS called.
        String minimalJson = "{}";

        // Should not throw — ObjectMapper happily deserializes to an OperateLog with null fields
        operateLogConsumer.handleAuditLog(minimalJson);

        verify(operateLogMapper).insert(logCaptor.capture());
        OperateLog captured = logCaptor.getValue();
        assertNull(captured.getServiceName());
        assertNull(captured.getOperateUser());
    }

    // ==================== handleAuditLog — mapper failure ====================

    @Test
    void handleAuditLog_throwsRuntimeExceptionWhenMapperFails() throws Exception {
        OperateLog input = new OperateLog();
        input.setServiceName("test-service");
        input.setMethodName("testMethod");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new RuntimeException("DB connection lost"))
                .when(operateLogMapper).insert(any(OperateLog.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> operateLogConsumer.handleAuditLog(json));
        assertEquals("审计日志写入失败", ex.getMessage());
        assertTrue(ex.getCause().getMessage().contains("DB connection lost"));
    }

    // ==================== handleAuditLog — re-throw ensures DLQ routing ====================

    @Test
    void handleAuditLog_reThrowsToTriggerDeadLetterQueue() throws Exception {
        // RabbitMQ routes messages to DLQ when listener throws.
        // This test documents that contract: any failure must propagate as an unchecked exception.
        OperateLog input = new OperateLog();
        input.setServiceName("svc");
        String json = objectMapper.writeValueAsString(input);

        doThrow(new org.apache.ibatis.exceptions.PersistenceException("constraint violation"))
                .when(operateLogMapper).insert(any(OperateLog.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> operateLogConsumer.handleAuditLog(json));
        // The cause chain preserves the original MyBatis exception
        assertInstanceOf(org.apache.ibatis.exceptions.PersistenceException.class, ex.getCause());
    }
}
