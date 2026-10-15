package uno.acloud.team.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.team.service.TeamUserCleanupService;

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
 * team 消费端测试：消费骨架已收敛到 {@code MqUserDeletedEventConsumerTemplate}
 * （60 号审计 🟠-4/🟡-5），本类钉住「委托转发 + 幂等三件套语义」在 team 侧生效。
 * 真实 mapper（findAndRegisterModules，与生产 Boot 自动配置同向），不再 mock ObjectMapper。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserDeletedEventConsumerTest {

    @Mock
    private TeamUserCleanupService cleanupService;

    private UserDeletedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        // 真实 mapper，不再 mock —— 反序列化走的是 Jackson 真实路径
        consumer = new UserDeletedEventConsumer(new ObjectMapper().findAndRegisterModules(), cleanupService);
    }

    private static final String DELETED_JSON =
            "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

    // ==================== wire 契约：发布端 record ↔ 消费端真实 JSON 往返 ====================

    /**
     * 发布端（user-service 用 UserDeletedEvent.of 构造）序列化出的 JSON，
     * team 消费端必须能无损读回 —— record 全字段往返断言（A4 修复的核心断言）。
     */
    @Test
    void wireContract_publisherPayload_roundTripsThroughRealMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UserDeletedEvent published = UserDeletedEvent.of(42L, "张三");

        String json = mapper.writeValueAsString(published);
        UserDeletedEvent consumed = mapper.readValue(json, UserDeletedEvent.class);

        assertEquals(published, consumed, "事件 record 必须可无损往返（含 eventType/version/timestamp 默认值补全）");
        assertEquals(RabbitMqConstants.ROUTING_KEY_USER_DELETED, consumed.eventType());
        assertEquals(1, consumed.version());
        assertEquals(42L, consumed.userId());
        assertEquals("张三", consumed.username());
    }

    // ==================== 正常消费路径 ====================

    @Test
    void handleUserEvent_validEvent_callsCleanupAndMarksCompleted() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);

        consumer.handleUserEvent(DELETED_JSON);

        verify(cleanupService).cleanup(1L);
        verify(cleanupService).markCleanupCompleted(1L);
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_duplicateAlreadyCompleted_skipsProcessing() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);
        when(cleanupService.isCleanupCompleted(1L)).thenReturn(true);

        assertDoesNotThrow(() -> consumer.handleUserEvent(DELETED_JSON));

        verify(cleanupService, never()).cleanup(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_cleanupThrows_releasesKeyAndRethrows() {
        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanupService).cleanup(1L);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> consumer.handleUserEvent(DELETED_JSON));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        // 业务失败释放幂等键，让 MQ 重投时能重新处理；失败路径绝不写 done
        verify(cleanupService).releaseIdempotencyKey(1L);
        verify(cleanupService, never()).markCleanupCompleted(anyLong());
    }

    // ==================== 毒消息路径（A4 明确要求） ====================

    @Test
    void handleUserEvent_invalidJson_throwsAmqpRejectAndDontRequeue() {
        // 坏 JSON 走真实 mapper：反序列化失败 → AmqpRejectAndDontRequeueException → 进 DLQ，
        // 不允许 log-and-return（静默 ACK 毒消息）
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleUserEvent("{bad json"));

        verifyNoInteractions(cleanupService);
    }

    @Test
    void handleUserEvent_typeMismatchedJson_alsoPoison_rejectsWithoutRequeue() {
        // username 传对象而非字符串 —— 类型不匹配同样是毒消息，必须进 DLQ
        String poison = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":{\"nested\":true}}";

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleUserEvent(poison));

        verifyNoInteractions(cleanupService);
    }

    @Test
    void handleUserEvent_nonDeleteEvent_ignored() {
        // 事件体反序列化成功但 eventType 非 user.deleted：按设计忽略（不占用幂等键）
        consumer.handleUserEvent(
                "{\"eventType\":\"user.registered\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}");

        verify(cleanupService, never()).tryAcquireIdempotencyKey(anyLong());
        verify(cleanupService, never()).cleanup(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }
}
