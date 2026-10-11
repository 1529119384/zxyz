package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.im.application.InternalUserProfileSyncService;
import uno.acloud.im.dto.InternalUserProfileSyncRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserEventConsumer} 单测。
 *
 * <p>⚠️ 反序列化一律用<b>真实配置 ObjectMapper</b>（不 mock）：
 * {@code UserProfileUpdatedEvent} / {@code UserDeletedEvent} 的 wire 契约在此逐字段验证，
 * 对齐 54 号 A1「mock 掉 ObjectMapper = 反序列化能力零验证」的假证据模式。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

    @Mock
    private InternalUserProfileSyncService userProfileSyncService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserEventConsumer(new ObjectMapper(), userProfileSyncService, redisTemplate);
    }

    private void stubIdempotencyKeyAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    // ==================== user.profile.updated ====================

    @Test
    void handleProfileUpdated_syncsProfileThroughRealDeserialization() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"user.profile.updated","version":1,"timestamp":1745732712000,
                 "userId":100,"username":"alice","name":"Alice","email":"a@x.com","avatar":"a.png"}
                """;

        consumer.handleUserEvent(json);

        verify(userProfileSyncService).syncUserProfile(org.mockito.ArgumentMatchers.argThat(
                (InternalUserProfileSyncRequest r) -> r.getUserId().equals(100L)
                        && "alice".equals(r.getUsername())
                        && "Alice".equals(r.getName())
                        && "a@x.com".equals(r.getEmail())
                        && "a.png".equals(r.getAvatar())));
    }

    // ==================== user.deleted ====================

    @Test
    void handleUserDeleted_removesProfile() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"user.deleted","version":1,"timestamp":1745732712000,
                 "userId":100,"username":"alice"}
                """;

        consumer.handleUserEvent(json);

        verify(userProfileSyncService).removeUserProfile(100L);
    }

    // ==================== 幂等 ====================

    @Test
    void handle_duplicateEvent_skipsProcessing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(contains("mq:idempotent:user:"), eq("1"), anyLong(), any(TimeUnit.class)))
                .thenReturn(false);
        String json = """
                {"eventType":"user.profile.updated","version":1,"timestamp":1745732712000,
                 "userId":100,"username":"alice","name":"Alice","email":"a@x.com","avatar":null}
                """;

        assertDoesNotThrow(() -> consumer.handleUserEvent(json));
        verifyNoInteractions(userProfileSyncService);
    }

    @Test
    void handle_unknownEventType_processedAsNoOp() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"user.registered","version":1,"timestamp":1745732712000,
                 "userId":100,"username":"alice","name":"Alice","email":"a@x.com","avatar":null}
                """;

        assertDoesNotThrow(() -> consumer.handleUserEvent(json));
        verifyNoInteractions(userProfileSyncService);
    }

    @Test
    void handle_emptyEventType_droppedWithoutProcessing() {
        // ⚠️ 缺失 eventType 字段会被 record 紧凑构造器兜底为默认路由键（不是 null），
        // 所以「缺 eventType」分支的真实触发形态是显式空串。
        String json = """
                {"eventType":"","version":1,"timestamp":1745732712000,"userId":100,"username":"alice"}
                """;

        assertDoesNotThrow(() -> consumer.handleUserEvent(json));
        verifyNoInteractions(userProfileSyncService);
    }

    // ==================== 失败路径 ====================

    @Test
    void handle_syncFailure_releasesIdempotencyKeyAndRethrows() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"user.profile.updated","version":1,"timestamp":1745732712000,
                 "userId":100,"username":"alice","name":"Alice","email":"a@x.com","avatar":null}
                """;
        doThrow(new RuntimeException("db down")).when(userProfileSyncService).syncUserProfile(any());

        assertThrows(RuntimeException.class, () -> consumer.handleUserEvent(json));

        // 失败必须释放幂等占位键，否则重投会被判为「重复」而静默丢弃
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void handle_malformedJson_throwsAmqpRejectAndDontRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.handleUserEvent("{bad json"));

        verifyNoInteractions(userProfileSyncService);
    }

    @Test
    void handle_typeMismatchPayload_throwsAmqpRejectAndDontRequeue() {
        // userId 类型不兼容（字符串 "abc"）：真实 ObjectMapper 反序列化必须失败 → 毒消息进 DLQ
        String json = """
                {"eventType":"user.profile.updated","version":1,"timestamp":1745732712000,
                 "userId":"abc","username":"alice"}
                """;

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleUserEvent(json));
        verifyNoInteractions(userProfileSyncService);
    }
}
