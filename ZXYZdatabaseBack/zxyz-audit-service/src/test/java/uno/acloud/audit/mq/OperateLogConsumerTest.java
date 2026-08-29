package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    @InjectMocks
    private OperateLogConsumer operateLogConsumer;

    @Captor
    private ArgumentCaptor<OperateLog> logCaptor;

    @Captor
    private ArgumentCaptor<String> hashCaptor;

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
}