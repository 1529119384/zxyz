package uno.acloud.audit.mq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditDlqConsumer} 唯一的产出是那条 ERROR 日志 —— 而这条日志正是运维定位毒消息的
 * 第一手线索（原始交换机/队列/原因）。日志内容即契约，所以要断言它，不能只"跑一遍不报错"。
 */
class AuditDlqConsumerTest {

    private final AuditDlqConsumer consumer = new AuditDlqConsumer();

    private static ILoggingEvent captureSingleLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AuditDlqConsumer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    void handleDeadLetter_logsOriginalExchangeQueueAndReason() {
        ILoggingEvent event = captureSingleLog(() -> consumer.handleDeadLetter("payload", Map.of(
                "x-first-death-exchange", "zxyz.audit.exchange",
                "x-first-death-queue", "zxyz.audit-operate-log",
                "x-first-death-reason", "rejected")));

        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage())
                .contains("zxyz.audit.exchange")
                .contains("zxyz.audit-operate-log")
                .contains("rejected")
                .contains("messageLength=7");
    }

    @Test
    void handleDeadLetter_whenHeadersMissing_stillLogsUnknowns() {
        // 死信头可能因版本/配置差异缺失，此时也必须留痕而不是 NPE 掉
        ILoggingEvent event = captureSingleLog(() -> consumer.handleDeadLetter("payload", Map.of()));

        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).contains("messageLength=7");
    }

    @Test
    void handleDeadLetter_whenMessageNull_reportsZeroLengthInsteadOfNpe() {
        ILoggingEvent event = captureSingleLog(() -> consumer.handleDeadLetter(null, Map.of()));

        assertThat(event.getFormattedMessage()).contains("messageLength=0");
    }
}
