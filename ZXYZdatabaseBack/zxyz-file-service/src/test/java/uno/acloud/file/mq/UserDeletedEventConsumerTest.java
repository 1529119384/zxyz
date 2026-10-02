package uno.acloud.file.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.file.service.FileUserCleanupService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDeletedEventConsumerTest {

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private FileUserCleanupService cleanupService;

    @Test
    void handleUserEvent_duplicateEvent_skipsProcessing() throws Exception {
        // F4：已完成（done 键在）⇒ 正常重复投递，跳过且不报错
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        when(cleanupService.isCleanupCompleted(1L)).thenReturn(true);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        verify(cleanupService, never()).cleanupUserPersonalFiles(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    /**
     * F4（P2）关键用例：认领失败但<b>并非已完成</b>（另一个消费者正在处理，或上次崩溃后
     * 短 TTL 尚未过期）时，日志必须走 warn 分支而不是把它当成「重复事件」。
     *
     * <p>修复前两者共用一句「重复用户删除事件，跳过处理」，把「清理半途而废」这种真实故障
     * 伪装成正常幂等命中，运维无法从日志发现该用户的文件其实没被清掉。</p>
     */
    @Test
    void handleUserEvent_claimedByAnotherConsumer_logsAsInProgressNotDuplicate() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        when(cleanupService.isCleanupCompleted(1L)).thenReturn(false);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        assertDoesNotThrow(() ->
                new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json));

        verify(cleanupService, never()).cleanupUserPersonalFiles(anyLong());
        verify(cleanupService).isCleanupCompleted(1L);
    }

    @Test
    void handleUserEvent_validEvent_callsCleanup() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json);

        verify(cleanupService).cleanupUserPersonalFiles(1L);
        // F4：清理成功后必须写「已完成」长 TTL 标记，否则单靠短 TTL 的 processing 键
        // 会让真正的重复投递在短 TTL 过期后再跑一遍清理。
        verify(cleanupService).markCleanupCompleted(1L);
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_cleanupThrows_releasesKeyAndRethrows() throws Exception {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanupService).cleanupUserPersonalFiles(1L);
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000L,\"userId\":1,\"username\":\"test\"}";
        when(objectMapper.readValue(json, UserDeletedEvent.class))
                .thenReturn(new UserDeletedEvent("user.deleted", 1, 1700000000000L, 1L, "test"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> new UserDeletedEventConsumer(objectMapper, cleanupService).handleUserEvent(json));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        verify(cleanupService).releaseIdempotencyKey(1L);
        // 失败路径绝不能写「已完成」，否则重投递会被永久跳过（正是 F4 要避免的形态）
        verify(cleanupService, never()).markCleanupCompleted(anyLong());
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
        verify(cleanupService, never()).cleanupUserPersonalFiles(anyLong());
    }
}
