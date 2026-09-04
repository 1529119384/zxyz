package uno.acloud.share.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uno.acloud.common.event.UserProfileUpdatedEvent;
import uno.acloud.share.service.ShareUserProfileSyncService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N4 回归测试：用户资料更新事件的 Redis 幂等语义。
 *
 * <p>幂等标记的「值」是最后一次成功应用的用户名，据此区分
 * 「同一条消息被重复投递」与「用户确实改了名」—— 后者必须放行同步。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProfileUpdatedEventConsumerTest {

    private static final String IDEMPOTENCY_KEY = "mq:idempotent:user:profile:1";

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ShareUserProfileSyncService userProfileSyncService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserProfileUpdatedEventConsumer consumer;

    /** 用 Map 模拟 Redis 的 KV 语义（get / set / delete） */
    private final Map<String, String> redis = new HashMap<>();

    @BeforeEach
    void setUp() {
        consumer = new UserProfileUpdatedEventConsumer(objectMapper, userProfileSyncService, redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(any())).thenAnswer(inv -> redis.get(inv.getArgument(0, String.class)));
        // set 返回 void，必须用 doAnswer().when() 而非 when()
        doAnswer(inv -> {
            redis.put(inv.getArgument(0, String.class), inv.getArgument(1, String.class));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> {
            redis.remove(inv.getArgument(0, String.class));
            return Boolean.TRUE;
        });
    }

    private void givenEvent(long userId, String username) throws Exception {
        when(objectMapper.readValue(anyString(), eq(UserProfileUpdatedEvent.class)))
                .thenReturn(UserProfileUpdatedEvent.of(userId, username, null, null, null));
    }

    @Test
    void sameUsernameRedelivery_shouldSkipSync() throws Exception {
        givenEvent(1L, "alice");

        consumer.handleUserProfileUpdated("msg-1");
        consumer.handleUserProfileUpdated("msg-1"); // 同一条消息重复投递

        verify(userProfileSyncService, times(1)).syncUsername(1L, "alice");
    }

    @Test
    void renameWithinSameHour_shouldSyncAgain() throws Exception {
        givenEvent(1L, "alice");
        consumer.handleUserProfileUpdated("msg-1");

        givenEvent(1L, "bob"); // TTL 内二次改名
        consumer.handleUserProfileUpdated("msg-2");

        // 旧实现（key 只含 userId、值恒为 "1"）会在此处把 bob 当成重复投递静默丢弃
        verify(userProfileSyncService).syncUsername(1L, "alice");
        verify(userProfileSyncService).syncUsername(1L, "bob");
    }

    @Test
    void renameBackToOriginal_shouldSyncAgain() throws Exception {
        givenEvent(1L, "alice");
        consumer.handleUserProfileUpdated("msg-1");
        givenEvent(1L, "bob");
        consumer.handleUserProfileUpdated("msg-2");
        givenEvent(1L, "alice"); // 改回原名
        consumer.handleUserProfileUpdated("msg-3");

        // 若采用「把 username 拼进 key」的方案，第三次会因 `...:alice` 键仍在 TTL 内被跳过
        verify(userProfileSyncService, times(2)).syncUsername(1L, "alice");
        verify(userProfileSyncService, times(1)).syncUsername(1L, "bob");
    }

    @Test
    void syncFailure_shouldReleaseIdempotencyMarkForRetry() throws Exception {
        givenEvent(1L, "alice");
        doThrow(new RuntimeException("DB down")).when(userProfileSyncService).syncUsername(1L, "alice");

        assertThrows(RuntimeException.class, () -> consumer.handleUserProfileUpdated("msg-1"));

        assertNull(redis.get(IDEMPOTENCY_KEY),
                "落库失败必须释放幂等标记，否则重试消息会被永久跳过");
    }
}
