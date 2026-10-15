package uno.acloud.common.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

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
 * {@link MqUserDeletedEventConsumerTemplate} 行为测试 —— 钉住消费骨架的六条既定语义：
 * 事件过滤不占幂等键、认领失败双分支日志、成功才写 done、失败释放并重抛、毒消息进 DLQ、
 * 释放失败不掩盖原始业务异常。四处消费端（file/project/share/team）共用本实现，
 * 这里的断言等价于在四处同时生效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MqUserDeletedEventConsumerTemplateTest {

    @Mock
    private MqUserDeletedEventConsumerTemplate.Cleanup cleanup;

    private MqUserDeletedEventConsumerTemplate template;

    @BeforeEach
    void setUp() {
        // 真实 mapper（不 mock ObjectMapper —— 本门禁由 MqConsumerTestDeserializationGuardTest 把守）
        template = new MqUserDeletedEventConsumerTemplate(new ObjectMapper().findAndRegisterModules(), cleanup);
    }

    private static final String DELETED_JSON =
            "{\"eventType\":\"user.deleted\",\"version\":1,\"timestamp\":1700000000000,\"userId\":42,\"username\":\"u42\"}";
    private static final String OTHER_JSON =
            "{\"eventType\":\"user.registered\",\"version\":1,\"timestamp\":1700000000000,\"userId\":42,\"username\":\"u42\"}";

    @Test
    void validEvent_runsCleanupAndMarksCompleted() {
        when(cleanup.tryAcquireIdempotencyKey(42L)).thenReturn(true);

        template.handleUserEvent(DELETED_JSON);

        verify(cleanup).cleanup(42L);
        verify(cleanup).markCleanupCompleted(42L);
        verify(cleanup, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void duplicateEventAlreadyCompleted_skipsSilently() {
        when(cleanup.tryAcquireIdempotencyKey(42L)).thenReturn(false);
        when(cleanup.isCleanupCompleted(42L)).thenReturn(true);

        assertDoesNotThrow(() -> template.handleUserEvent(DELETED_JSON));

        verify(cleanup, never()).cleanup(anyLong());
        verify(cleanup, never()).releaseIdempotencyKey(anyLong());
    }

    @Test
    void duplicateEventStillProcessing_skipsWithoutCleanup() {
        when(cleanup.tryAcquireIdempotencyKey(42L)).thenReturn(false);
        when(cleanup.isCleanupCompleted(42L)).thenReturn(false);

        assertDoesNotThrow(() -> template.handleUserEvent(DELETED_JSON));

        verify(cleanup, never()).cleanup(anyLong());
        verify(cleanup).isCleanupCompleted(42L);
    }

    @Test
    void cleanupFailure_releasesClaimAndRethrows_withoutDoneMarker() {
        when(cleanup.tryAcquireIdempotencyKey(42L)).thenReturn(true);
        doThrow(new RuntimeException("DB error")).when(cleanup).cleanup(42L);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> template.handleUserEvent(DELETED_JSON));
        assertEquals("处理用户删除事件消息失败", ex.getMessage());

        verify(cleanup).releaseIdempotencyKey(42L);
        verify(cleanup, never()).markCleanupCompleted(anyLong());
    }

    @Test
    void invalidJson_isPoison_rejectsWithoutRequeue() {
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> template.handleUserEvent("{bad json"));

        verifyNoInteractions(cleanup);
    }

    @Test
    void nonDeleteEvent_ignoredWithoutClaimingKey() {
        template.handleUserEvent(OTHER_JSON);

        verify(cleanup, never()).tryAcquireIdempotencyKey(anyLong());
        verify(cleanup, never()).cleanup(anyLong());
    }

    @Test
    void markCompletedFailure_stillCountsAsPoisonPath_releasesAndRethrows() {
        // done 键写入失败同样属于「清理未稳定完成」，必须释放认领并重抛让容器重试
        when(cleanup.tryAcquireIdempotencyKey(42L)).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(cleanup).markCleanupCompleted(42L);

        assertThrows(RuntimeException.class, () -> template.handleUserEvent(DELETED_JSON));

        verify(cleanup).releaseIdempotencyKey(42L);
    }
}
