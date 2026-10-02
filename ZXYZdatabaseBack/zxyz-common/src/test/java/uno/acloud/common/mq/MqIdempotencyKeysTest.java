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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link MqIdempotencyKeys} 行为测试。
 *
 * <p>核心断言：<b>Redis 释放失败时不得向上抛异常</b>。本方法在异常处理路径上被调用，
 * 抛出会掩盖原始业务异常 —— 这是三份复制实现共同依赖、却从未被测试钉住的语义。</p>
 */
class MqIdempotencyKeysTest {

    private StringRedisTemplate redisTemplate;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        logger = (Logger) LoggerFactory.getLogger("test.mq.idempotency");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void release_deletesKeyAndLogsWarn() {
        MqIdempotencyKeys.release(redisTemplate, "mq:idempotent:user:7", logger);

        verify(redisTemplate).delete("mq:idempotent:user:7");
        assertTrue(appender.list.stream()
                        .anyMatch(event -> event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("mq:idempotent:user:7")),
                "释放成功应记 WARN 并带上 key，实际：" + appender.list);
    }

    @Test
    void release_swallowsRedisFailureAndLogsError() {
        doThrow(new RuntimeException("Redis connection reset"))
                .when(redisTemplate).delete("mq:idempotent:team:9");

        // 关键：不得抛出 —— 调用方正处在 catch 块里，抛出会盖掉原始业务异常
        assertDoesNotThrow(() -> MqIdempotencyKeys.release(redisTemplate, "mq:idempotent:team:9", logger));

        assertTrue(appender.list.stream()
                        .anyMatch(event -> event.getLevel() == Level.ERROR
                                && event.getFormattedMessage().contains("mq:idempotent:team:9")),
                "释放失败必须记 ERROR（重投会被误判为重复而跳过），实际：" + appender.list);
    }

    @Test
    void release_successDoesNotLogError() {
        MqIdempotencyKeys.release(redisTemplate, "key-1", logger);

        assertEquals(0, appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count(),
                "成功路径不得产生 ERROR 噪音");
    }
}
