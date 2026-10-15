package uno.acloud.file.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.file.service.FileUserCleanupService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * file 消费端测试：消费骨架与幂等三件套已收敛到 common
 * （{@code MqUserDeletedEventConsumerTemplate} / {@code MqUserDeletedCleanupSupport}，
 * 60 号审计 🟠-4/🟡-5），本类钉住「委托转发 + F4 双分支语义」在 file 侧生效。
 * 真实 mapper（findAndRegisterModules，与生产 Boot 自动配置同向），不再 mock ObjectMapper。
 */
@ExtendWith(MockitoExtension.class)
class UserDeletedEventConsumerTest {

    @Mock
    private FileUserCleanupService cleanupService;

    private UserDeletedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserDeletedEventConsumer(new ObjectMapper().findAndRegisterModules(), cleanupService);
    }

    private static final String DELETED_JSON =
            "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

    @Test
    void handleUserEvent_duplicateAlreadyCompleted_skipsProcessing() {
        // F4：已完成（done 键在）⇒ 正常重复投递，跳过且不报错
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        when(cleanupService.isCleanupCompleted(1L)).thenReturn(true);

        assertDoesNotThrow(() -> consumer.handleUserEvent(DELETED_JSON));

        verify(cleanupService, never()).cleanup(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    /**
     * F4（P2）关键用例：认领失败但<b>并非已完成</b>（另一个消费者正在处理，或上次崩溃后
     * 短 TTL 尚未过期）时，模板必须走 warn 分支而不是把它当成「重复事件」。
     */
    @Test
    void handleUserEvent_claimedByAnotherConsumer_logsAsInProgressNotDuplicate() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        when(cleanupService.isCleanupCompleted(1L)).thenReturn(false);

        assertDoesNotThrow(() -> consumer.handleUserEvent(DELETED_JSON));

        verify(cleanupService, never()).cleanup(anyLong());
        verify(cleanupService).isCleanupCompleted(1L);
    }

    @Test
    void handleUserEvent_validEvent_callsCleanupAndMarksCompleted() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);

        consumer.handleUserEvent(DELETED_JSON);

        verify(cleanupService).cleanup(1L);
        // F4：清理成功后必须写「已完成」长 TTL 标记
        verify(cleanupService).markCleanupCompleted(1L);
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_cleanupThrows_releasesKeyAndRethrows() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanupService).cleanup(1L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> consumer.handleUserEvent(DELETED_JSON));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        verify(cleanupService).releaseIdempotencyKey(1L);
        // 失败路径绝不能写「已完成」，否则重投递会被永久跳过（正是 F4 要避免的形态）
        verify(cleanupService, never()).markCleanupCompleted(anyLong());
    }

    @Test
    void handleUserEvent_invalidJson_throwsAmqpRejectAndDontRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.handleUserEvent("{bad"));

        verifyNoInteractions(cleanupService);
    }

    @Test
    void handleUserEvent_nonDeleteEvent_ignored() {
        consumer.handleUserEvent(
                "{\"eventType\":\"user.registered\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}");

        verify(cleanupService, never()).tryAcquireIdempotencyKey(anyLong());
        verify(cleanupService, never()).cleanup(anyLong());
    }

    /** wire 契约：发布端 record → 真实 mapper 往返无损（事件 record 加字段时的回归网）。 */
    @Test
    void wireContract_publisherPayload_roundTripsThroughRealMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserDeletedEvent published = UserDeletedEvent.of(42L, "张三");

        String json = mapper.writeValueAsString(published);
        UserDeletedEvent consumed = mapper.readValue(json, UserDeletedEvent.class);

        assertEquals(published, consumed);
        assertEquals(RabbitMqConstants.ROUTING_KEY_USER_DELETED, consumed.eventType());
    }
}
