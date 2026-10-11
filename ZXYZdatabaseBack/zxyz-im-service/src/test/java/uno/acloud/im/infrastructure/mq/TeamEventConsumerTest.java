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
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.im.application.InternalTeamSyncService;
import uno.acloud.im.dto.InternalTeamMemberRemovalRequest;
import uno.acloud.im.dto.InternalTeamMemberSyncRequest;
import uno.acloud.im.dto.InternalTeamSyncRequest;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TeamEventConsumer} 单测。
 *
 * <p>⚠️ 反序列化一律用<b>真实配置 ObjectMapper</b>（不 mock）：
 * 四种团队事件 record 的 wire 契约（eventType/version/timestamp + 业务字段）在此逐字段验证 ——
 * 对齐 54 号 A1「mock 掉 ObjectMapper = 反序列化能力零验证」的假证据模式。</p>
 */
@ExtendWith(MockitoExtension.class)
class TeamEventConsumerTest {

    @Mock
    private InternalTeamSyncService internalTeamSyncService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private TeamEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new TeamEventConsumer(new ObjectMapper(), internalTeamSyncService, redisTemplate);
    }

    private void stubIdempotencyKeyAcquired() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(true);
    }

    // ==================== team.created ====================

    @Test
    void handleTeamCreated_syncsTeamThroughRealDeserialization() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"team.created","version":1,"timestamp":1745732712000,
                 "teamId":5,"name":"My Team","avatar":"a.png","description":"desc",
                 "ownerUserId":100,"ownerUsername":"owner","ownerName":"Own Er","ownerEmail":"o@x.com"}
                """;

        consumer.handleTeamEvent(json);

        verify(internalTeamSyncService).syncTeam(org.mockito.ArgumentMatchers.argThat(
                (InternalTeamSyncRequest r) -> r.getTeamId().equals(5L)
                        && "My Team".equals(r.getName())
                        && r.getOwnerUserId().equals(100L)
                        && "owner".equals(r.getOwnerUsername())
                        && "Own Er".equals(r.getOwnerName())
                        && "o@x.com".equals(r.getOwnerEmail())));
    }

    // ==================== team.updated ====================

    @Test
    void handleTeamUpdated_syncsTeamProfile() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"team.updated","version":1,"timestamp":1745732712000,
                 "teamId":5,"name":"New Name","avatar":"b.png","description":"new desc",
                 "ownerUserId":100,"ownerUsername":"owner","ownerName":"Own Er","ownerEmail":"o@x.com"}
                """;

        consumer.handleTeamEvent(json);

        verify(internalTeamSyncService).syncTeamProfile(org.mockito.ArgumentMatchers.argThat(
                (InternalTeamSyncRequest r) -> r.getTeamId().equals(5L)
                        && "New Name".equals(r.getName())
                        && "new desc".equals(r.getDescription())));
    }

    // ==================== team.member.added ====================

    @Test
    void handleMemberAdded_syncsMember() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"team.member.added","version":1,"timestamp":1745732712000,
                 "teamId":5,"userId":200,"username":"alice","name":"Alice","email":"a@x.com",
                 "avatar":"a.png","roleCode":"MEMBER","sequenceNumber":7}
                """;

        consumer.handleTeamEvent(json);

        verify(internalTeamSyncService).syncMember(org.mockito.ArgumentMatchers.argThat(
                (InternalTeamMemberSyncRequest r) -> r.getTeamId().equals(5L)
                        && r.getUserId().equals(200L)
                        && "alice".equals(r.getUsername())
                        && "MEMBER".equals(r.getRoleCode())));
    }

    // ==================== team.member.removed ====================

    @Test
    void handleMemberRemoved_removesMember() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"team.member.removed","version":1,"timestamp":1745732712000,
                 "teamId":5,"userId":200,"sequenceNumber":8}
                """;

        consumer.handleTeamEvent(json);

        verify(internalTeamSyncService).removeMember(org.mockito.ArgumentMatchers.argThat(
                (InternalTeamMemberRemovalRequest r) -> r.getTeamId().equals(5L)
                        && r.getUserId().equals(200L)));
    }

    // ==================== 幂等 / 乱序 ====================

    @Test
    void handle_duplicateEvent_skipsProcessing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(false);
        String json = """
                {"eventType":"team.created","version":1,"timestamp":1745732712000,"teamId":5,
                 "name":"My Team","avatar":null,"description":null,"ownerUserId":100,
                 "ownerUsername":"o","ownerName":"O","ownerEmail":"o@x.com"}
                """;

        assertDoesNotThrow(() -> consumer.handleTeamEvent(json));
        verifyNoInteractions(internalTeamSyncService);
    }

    @Test
    void handle_outOfSequenceEvent_discarded() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(valueOperations.get("mq:sequence:team:5")).thenReturn("9");
        String json = """
                {"eventType":"team.member.added","version":1,"timestamp":1745732712000,
                 "teamId":5,"userId":200,"username":"alice","name":"Alice","email":"a@x.com",
                 "avatar":null,"roleCode":"MEMBER","sequenceNumber":3}
                """;

        assertDoesNotThrow(() -> consumer.handleTeamEvent(json));
        verifyNoInteractions(internalTeamSyncService);
    }

    @Test
    void handle_unknownEventType_processedAsNoOpAndSequenceAdvances() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), anyLong(), any(TimeUnit.class))).thenReturn(true);
        String json = """
                {"eventType":"team.disbanded","version":1,"timestamp":1745732712000,
                 "teamId":5,"sequenceNumber":4}
                """;

        assertDoesNotThrow(() -> consumer.handleTeamEvent(json));
        verifyNoInteractions(internalTeamSyncService);
        // 未知类型也算「已处理」，序列号推进防旧事件回放
        verify(valueOperations).set(eq("mq:sequence:team:5"), eq("4"), eq(24L), eq(TimeUnit.HOURS));
    }

    // ==================== 失败路径 ====================

    @Test
    void handle_syncFailure_releasesIdempotencyKeyAndRethrows() {
        stubIdempotencyKeyAcquired();
        String json = """
                {"eventType":"team.created","version":1,"timestamp":1745732712000,"teamId":5,
                 "name":"My Team","avatar":null,"description":null,"ownerUserId":100,
                 "ownerUsername":"o","ownerName":"O","ownerEmail":"o@x.com"}
                """;
        doThrow(new RuntimeException("db down")).when(internalTeamSyncService).syncTeam(any());

        assertThrows(RuntimeException.class, () -> consumer.handleTeamEvent(json));

        // 失败必须释放幂等占位键，否则重投会被判为「重复」而静默丢弃（数据永久分歧）
        verify(redisTemplate).delete(anyString());
    }

    @Test
    void handle_malformedJson_throwsAmqpRejectAndDontRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.handleTeamEvent("{bad json"));

        verifyNoInteractions(internalTeamSyncService);
    }

    @Test
    void handle_missingEventType_droppedWithoutProcessing() {
        String json = """
                {"version":1,"timestamp":1745732712000,"teamId":5,"name":"My Team"}
                """;

        assertDoesNotThrow(() -> consumer.handleTeamEvent(json));
        verifyNoInteractions(internalTeamSyncService);
        // 缺 eventType 在幂等占位之前就被丢弃，Redis 完全不被触碰
        verifyNoInteractions(redisTemplate);
    }
}
