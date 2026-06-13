package uno.acloud.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDir;

    private AuditEventPublisher publisher;

    private OperateLog createOperateLog() {
        OperateLog log = new OperateLog();
        log.setServiceName("test-service");
        log.setMethodName("testMethod");
        log.setOperateTime(LocalDateTime.now());
        return log;
    }

    @BeforeEach
    void setUp() {
        String fallbackPath = tempDir.resolve("audit-fallback.jsonl").toString();
        publisher = new AuditEventPublisher(rabbitTemplate, objectMapper, fallbackPath);
    }

    @Test
    void publish_successOnFirstAttempt() {
        OperateLog operateLog = createOperateLog();

        publisher.publish(operateLog);

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());
        assertEquals(0, publisher.getBufferSize());
    }

    @Test
    void publish_retriesThenSucceeds() {
        OperateLog operateLog = createOperateLog();
        doThrow(new RuntimeException("Connection lost"))
                .doThrow(new RuntimeException("Connection lost"))
                .doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        publisher.publish(operateLog);

        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), anyString());
        assertEquals(0, publisher.getBufferSize());
    }

    @Test
    void publish_allRetriesFail_writesToFallbackFile() throws Exception {
        OperateLog operateLog = createOperateLog();
        doThrow(new RuntimeException("Connection lost"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        publisher.publish(operateLog);

        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), anyString());
        assertEquals(0, publisher.getBufferSize());
        Path fallbackFile = tempDir.resolve("audit-fallback.jsonl");
        assertTrue(Files.exists(fallbackFile));
        String content = Files.readString(fallbackFile);
        assertTrue(content.contains("test-service"));
    }

    @Test
    void publish_allRetriesFail_fileWriteFails_addsToBuffer() throws Exception {
        OperateLog operateLog = createOperateLog();
        doThrow(new RuntimeException("Connection lost"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());
        // Create a read-only file to make write fail
        Path readOnlyFile = tempDir.resolve("readonly-audit.jsonl");
        Files.writeString(readOnlyFile, "existing content");
        readOnlyFile.toFile().setReadOnly();
        AuditEventPublisher failPublisher = new AuditEventPublisher(
                rabbitTemplate, objectMapper, readOnlyFile.toString());

        failPublisher.publish(operateLog);

        assertEquals(1, failPublisher.getBufferSize());
        // Cleanup
        readOnlyFile.toFile().setWritable(true);
    }

    @Test
    void publish_bufferFull_evictsOldest() throws Exception {
        AuditEventPublisher failPublisher = new AuditEventPublisher(
                rabbitTemplate, objectMapper, "/nonexistent/deep/path/audit.jsonl");

        // Fill buffer to max (10,000) using direct access to avoid slow retry sleeps
        for (int i = 0; i < 10_000; i++) {
            failPublisher.addToBufferForTest("event-" + i);
        }
        assertEquals(10_000, failPublisher.getBufferSize());

        // One more should evict oldest
        failPublisher.addToBufferForTest("event-overflow");
        assertEquals(10_000, failPublisher.getBufferSize());
    }

    @Test
    void retryBufferedEvents_emptyBuffer_returnsZero() {
        assertEquals(0, publisher.retryBufferedEvents());
    }

    @Test
    void retryBufferedEvents_success_removesFromBuffer() {
        publisher.addToBufferForTest("{\"test\":\"event1\"}");
        assertEquals(1, publisher.getBufferSize());

        int result = publisher.retryBufferedEvents();

        assertEquals(1, result);
        assertEquals(0, publisher.getBufferSize());
    }

    @Test
    void retryBufferedEvents_failure_keepsInBuffer() throws Exception {
        Path readOnlyFile = tempDir.resolve("retry-readonly.jsonl");
        Files.writeString(readOnlyFile, "x");
        readOnlyFile.toFile().setReadOnly();
        AuditEventPublisher failPublisher = new AuditEventPublisher(
                rabbitTemplate, objectMapper, readOnlyFile.toString());

        failPublisher.addToBufferForTest("{\"test\":\"event1\"}");
        assertEquals(1, failPublisher.getBufferSize());

        doThrow(new RuntimeException("Still down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        int result = failPublisher.retryBufferedEvents();

        assertEquals(0, result);
        assertEquals(1, failPublisher.getBufferSize());
        readOnlyFile.toFile().setWritable(true);
    }

    @Test
    void retryBufferedEvents_partialSuccess() throws Exception {
        Path readOnlyFile = tempDir.resolve("retry-partial-readonly.jsonl");
        Files.writeString(readOnlyFile, "x");
        readOnlyFile.toFile().setReadOnly();
        AuditEventPublisher failPublisher = new AuditEventPublisher(
                rabbitTemplate, objectMapper, readOnlyFile.toString());

        failPublisher.addToBufferForTest("{\"test\":\"event1\"}");
        failPublisher.addToBufferForTest("{\"test\":\"event2\"}");
        failPublisher.addToBufferForTest("{\"test\":\"event3\"}");
        assertEquals(3, failPublisher.getBufferSize());

        // 2 succeed, 1 fails
        doThrow(new RuntimeException("Still down"))
                .doNothing()
                .doNothing()
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        int result = failPublisher.retryBufferedEvents();

        assertEquals(2, result);
        assertEquals(1, failPublisher.getBufferSize());
        readOnlyFile.toFile().setWritable(true);
    }
}
