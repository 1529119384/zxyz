package uno.acloud.common.mq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

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
 * {@link MqUserDeletedCleanupSupport} 行为测试 —— 钉住 user.deleted 幂等三件套的公共语义。
 *
 * <p>本类是 60 号审计 🟠-4 的收敛点：file / project / share / team 四处消费端现在共用这一份实现，
 * 此处断言的每一条语义（短 TTL 认领、done 拦截、先 done 后删、失败不写 done、释放吞 Redis 异常）
 * 都等价于在四处同时生效。</p>
 */
class MqUserDeletedCleanupSupportTest {

    private static final String PROCESSING_PREFIX = "mq:idempotent:user:deleted:test:processing:";
    private static final String DONE_PREFIX = "mq:idempotent:user:deleted:test:done:";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        logger = (Logger) LoggerFactory.getLogger("test.mq.cleanup.support");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    /** 首次处理：认领成功，且必须是<b>短 TTL（分钟）</b>的 processing 键 —— 崩溃后快速恢复的关键。 */
    @Test
    void tryAcquire_usesShortProcessingTtl() {
        when(redisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(false);
        when(valueOperations.setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(true);

        assertTrue(MqUserDeletedCleanupSupport.tryAcquireIdempotencyKey(
                redisTemplate, PROCESSING_PREFIX, DONE_PREFIX, 7L));

        verify(valueOperations).setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.HOURS));
    }

    /** 已完成（done 键在）⇒ 直接拒绝，且不得再碰 processing 键。 */
    @Test
    void tryAcquire_returnsFalseWhenAlreadyCompleted() {
        when(redisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(true);

        assertFalse(MqUserDeletedCleanupSupport.tryAcquireIdempotencyKey(
                redisTemplate, PROCESSING_PREFIX, DONE_PREFIX, 7L));

        verify(redisTemplate, never()).opsForValue();
    }

    /** {@code setIfAbsent} 返回 null（连接异常等）必须按「未认领」处理。 */
    @Test
    void tryAcquire_treatsNullResultAsNotAcquired() {
        when(redisTemplate.hasKey(DONE_PREFIX + 7L)).thenReturn(false);
        when(valueOperations.setIfAbsent(eq(PROCESSING_PREFIX + 7L), anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

        assertFalse(MqUserDeletedCleanupSupport.tryAcquireIdempotencyKey(
                redisTemplate, PROCESSING_PREFIX, DONE_PREFIX, 7L));
    }

    /** 成功路径：先写长 TTL done 键、再删 processing 键，顺序不可颠倒。 */
    @Test
    void markCompleted_writesDoneThenReleasesProcessing() {
        MqUserDeletedCleanupSupport.markCleanupCompleted(
                redisTemplate, PROCESSING_PREFIX, DONE_PREFIX, 7L);

        verify(valueOperations).set(eq(DONE_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.HOURS));
        verify(redisTemplate).delete(PROCESSING_PREFIX + 7L);
        inOrder(valueOperations, redisTemplate).verify(valueOperations)
                .set(eq(DONE_PREFIX + 7L), anyString(), anyLong(), eq(TimeUnit.HOURS));
        inOrder(redisTemplate).verify(redisTemplate).delete(PROCESSING_PREFIX + 7L);
    }

    /** 失败回滚路径只释放 processing 键，绝不写 done。 */
    @Test
    void release_neverWritesDoneMarker() {
        MqUserDeletedCleanupSupport.releaseIdempotencyKey(redisTemplate, PROCESSING_PREFIX, 7L, logger);

        verify(redisTemplate).delete(PROCESSING_PREFIX + 7L);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /** 释放路径的 Redis 异常必须吞掉（复用 MqIdempotencyKeys 语义），并记 ERROR。 */
    @Test
    void release_swallowsRedisFailureAndLogsError() {
        doThrow(new RuntimeException("Redis connection reset"))
                .when(redisTemplate).delete(PROCESSING_PREFIX + 9L);

        assertDoesNotThrow(() -> MqUserDeletedCleanupSupport.releaseIdempotencyKey(
                redisTemplate, PROCESSING_PREFIX, 9L, logger));

        assertTrue(appender.list.stream()
                        .anyMatch(event -> event.getLevel() == Level.ERROR
                                && event.getFormattedMessage().contains(PROCESSING_PREFIX + 9L)),
                "释放失败必须记 ERROR，实际：" + appender.list);
        assertEquals(0, appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count(),
                "成功路径不应有 WARN 噪音");
    }
}
