package uno.acloud.share.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.share.service.ShareUserCleanupService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDeletedEventConsumerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ShareUserCleanupService cleanupService;

    @Test
    void handleUserEvent_duplicateEvent_skipsProcessing() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        verify(cleanupService, never()).cleanupUserShares(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_validEvent_callsCleanup() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        verify(cleanupService).cleanupUserShares(1L);
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_cleanupThrows_releasesKeyAndRethrows() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanupService).cleanupUserShares(1L);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        verify(cleanupService).releaseIdempotencyKey(1L);
    }

    @Test
    void handleUserEvent_invalidJson_throwsAmqpRejectAndDontRequeue() throws Exception {
        when(objectMapper.readValue(anyString(), eq(UserDeletedEvent.class)))
                .thenThrow(new JsonProcessingException("bad json") {});

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent("{bad"));

        verifyNoInteractions(cleanupService);
    }

    @Test
    void handleUserEvent_nonDeleteEvent_ignored() throws Exception {
        String json = "{\"eventType\":\"user.registered\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.registered", 1, 1700000000000L, 1L, "test"));

        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        verify(cleanupService, never()).tryAcquireIdempotencyKey(anyLong());
        verify(cleanupService, never()).cleanupUserShares(anyLong());
    }
}
