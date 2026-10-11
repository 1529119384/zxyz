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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

/**
 * 54 号 §2.3 A1/A4 修复：旧版把 {@link ObjectMapper} 整个 mock（「测试补上运行时缺的东西」
 * 第 5 类假证据）—— 事件反序列化能力零验证，事件 record 加字段时全链静默炸。
 * 现改为真实配置 mapper（findAndRegisterModules，与生产 Boot 自动配置同向）+ 真实 JSON 样例
 * 往返断言 + 毒消息路径（坏 JSON → AmqpRejectAndDontRequeueException 进 DLQ）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserDeletedEventConsumerTest {

    @Mock
    private TeamUserCleanupService cleanupService;

    private ObjectMapper objectMapper;
    private UserDeletedEventConsumer consumer;

    @BeforeEach
    void setUp() {
        // 真实 mapper，不再 mock —— 反序列化走的是 Jackson 真实路径
        objectMapper = new ObjectMapper().findAndRegisterModules();
        consumer = new UserDeletedEventConsumer(objectMapper, cleanupService);
    }

    // ==================== wire 契约：发布端 record ↔ 消费端真实 JSON 往返 ====================

    /**
     * 发布端（user-service 用 UserDeletedEvent.of 构造）序列化出的 JSON，
     * team 消费端必须能无损读回 —— record 全字段往返断言（A4 修复的核心断言）。
     */
    @Test
    void wireContract_publisherPayload_roundTripsThroughRealMapper() throws Exception {
        UserDeletedEvent published = UserDeletedEvent.of(42L, "张三");

        String json = objectMapper.writeValueAsString(published);
        UserDeletedEvent consumed = objectMapper.readValue(json, UserDeletedEvent.class);

        assertEquals(published, consumed, "事件 record 必须可无损往返（含 eventType/version/timestamp 默认值补全）");
        assertEquals(RabbitMqConstants.ROUTING_KEY_USER_DELETED, consumed.eventType());
        assertEquals(1, consumed.version());
        assertEquals(42L, consumed.userId());
        assertEquals("张三", consumed.username());
    }

    // ==================== 正常消费路径 ====================

    @Test
    void handleUserEvent_validEvent_callsCleanup() {
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);

        consumer.handleUserEvent(json);

        verify(cleanupService).removeUserFromTeams(1L);
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_duplicateEvent_skipsProcessing() {
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(false);

        consumer.handleUserEvent(json);

        verify(cleanupService, never()).removeUserFromTeams(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void handleUserEvent_cleanupThrows_releasesKeyAndRethrows() {
        String json = "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

        when(cleanupService.tryAcquireIdempotencyKey(1L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanupService).removeUserFromTeams(1L);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> consumer.handleUserEvent(json));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        // 业务失败释放幂等键，让 MQ 重投时能重新处理
        verify(cleanupService).releaseIdempotencyKey(1L);
    }

    // ==================== 毒消息路径（A4 明确要求） ====================

    @Test
    void handleUserEvent_invalidJson_throwsAmqpRejectAndDontRequeue() {
        // 坏 JSON 走真实 mapper：反序列化失败 → AmqpRejectAndDontRequeueException → 进 DLQ，
        // 不允许 log-and-return（静默 ACK 毒消息）
        String poison = "{bad json";

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleUserEvent(poison));

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
        String json = "{\"eventType\":\"user.registered\",\"version\":1,\"timestamp\":1700000000000,\"userId\":1,\"username\":\"test\"}";

        consumer.handleUserEvent(json);

        verify(cleanupService, never()).tryAcquireIdempotencyKey(anyLong());
        verify(cleanupService, never()).removeUserFromTeams(anyLong());
        verify(cleanupService, never()).releaseIdempotencyKey(anyLong());
    }
}
