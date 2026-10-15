package uno.acloud.common.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MqOutboxPublisher} 行为测试（60 号审计 🟠-2 最小 outbox）。
 *
 * <p>钉住的核心语义：</p>
 * <ol>
 *   <li>enqueue：带 TTL 24h 落键；Redis 异常吞掉返回 false（不污染 afterCommit 失败路径）；</li>
 *   <li>retryPending：先发 MQ 再删键（顺序不可颠倒）；MQ 失败保留键等下一轮；</li>
 *   <li>只扫本服务前缀（多服务共用一个 Redis 实例互不干扰）；</li>
 *   <li> eventTypeOf 能从键名还原含点号的事件类型。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MqOutboxPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MqOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        publisher = new MqOutboxPublisher(redisTemplate, "zxyz-test-service") {
            @Override
            protected RabbitTemplate rabbitTemplate() {
                return rabbitTemplate;
            }
        };
    }

    // ==================== enqueue ====================

    @Test
    void enqueue_storesEventWith24hTtl() {
        assertTrue(publisher.enqueue("user.deleted", "{\"userId\":42}"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("{\"userId\":42}"), eq(MqOutboxPublisher.TTL));
        String key = keyCaptor.getValue();
        assertTrue(key.startsWith("mq:outbox:zxyz-test-service:user.deleted:"),
                "键必须带服务名 + 事件类型，实际: " + key);
        assertEquals(24, MqOutboxPublisher.TTL.toHours(),
                "outbox TTL 必须是 24h（与消费端 DLQ message-ttl 同量级）");
    }

    @Test
    void enqueue_redisFailure_swallowsAndReturnsFalse() {
        doThrow(new RuntimeException("redis down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertFalse(publisher.enqueue("team.created", "{}"));

        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    // ==================== retryPending ====================

    @Test
    void retryPending_sendsThenDeletesKey_inOrder() {
        String key = "mq:outbox:zxyz-test-service:user.deleted:uuid-1";
        mockScanReturning(List.of(key));
        when(valueOperations.get(key)).thenReturn("{\"userId\":42}");

        assertEquals(1, publisher.retryPending());

        verify(rabbitTemplate).convertAndSend(eq(uno.acloud.common.RabbitMqConstants.EXCHANGE),
                eq("user.deleted"), eq("{\"userId\":42}"));
        inOrder(rabbitTemplate, redisTemplate).verify(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), anyString());
        inOrder(redisTemplate).verify(redisTemplate).delete(key);
    }

    @Test
    void retryPending_sendFailure_keepsKeyForNextRound() {
        String key = "mq:outbox:zxyz-test-service:team.created:uuid-2";
        mockScanReturning(List.of(key));
        when(valueOperations.get(key)).thenReturn("{}");
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        assertEquals(0, publisher.retryPending());

        verify(redisTemplate, never()).delete(key);
    }

    @Test
    void retryPending_emptyOutbox_isNoOp() {
        mockScanReturning(List.of());

        assertEquals(0, publisher.retryPending());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
    }

    @Test
    void retryPending_scanFailure_returnsZeroWithoutThrowing() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("redis down"));

        assertEquals(0, publisher.retryPending());
    }

    @Test
    void retryPending_missingValue_treatedAsAlreadyHandled() {
        String key = "mq:outbox:zxyz-test-service:user.deleted:uuid-3";
        mockScanReturning(List.of(key));
        when(valueOperations.get(key)).thenReturn(null);

        assertEquals(0, publisher.retryPending());

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), anyString());
        verify(redisTemplate, never()).delete(key);
    }

    // ==================== 键名解析 ====================

    @Test
    void eventTypeOf_restoresDottedEventType() {
        assertEquals("team.member.added",
                MqOutboxPublisher.eventTypeOf("mq:outbox:zxyz-team-service:team.member.added:uuid-x", "zxyz-team-service"));
        assertEquals("user.deleted",
                MqOutboxPublisher.eventTypeOf("mq:outbox:zxyz-user-service:user.deleted:uuid-y", "zxyz-user-service"));
    }

    @Test
    void basePublisher_withoutRabbitTemplateOverride_failsLoudly() {
        MqOutboxPublisher bare = new MqOutboxPublisher(redisTemplate, "zxyz-bare");
        assertDoesNotThrow(bare::serviceName);
    }

    // ==================== 工具 ====================

    @SuppressWarnings("unchecked")
    private void mockScanReturning(List<String> keys) {
        Cursor<String> cursor = mock(Cursor.class);
        java.util.Iterator<String> iterator = keys.iterator();
        when(cursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
        when(cursor.next()).thenAnswer(inv -> iterator.next());
        // 关闭资源时不得抛异常
        lenient().doNothing().when(cursor).close();
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
    }
}
