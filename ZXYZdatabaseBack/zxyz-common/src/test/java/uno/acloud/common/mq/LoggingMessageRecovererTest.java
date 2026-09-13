package uno.acloud.common.mq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoggingMessageRecoverer} 单元测试（审计 L7 修复配套）。
 *
 * <p>这里要同时守住两件事，缺一不可：</p>
 * <ol>
 *   <li><strong>必须真的打出一条 ERROR，且上下文齐全</strong>（service / queue / messageId /
 *       payload 摘要 / 次数）。日志本身是这个口径下唯一的可观测信号，字段漏一个就等于没修。</li>
 *   <li><strong>必须保持投递语义不变</strong>：末尾仍走 Spring AMQP 自带的
 *       {@code RejectAndDontRequeueRecoverer}，即抛 {@link ListenerExecutionFailedException}
 *       （cause 为 {@link AmqpRejectAndDontRequeueException}）—— 容器据此拒绝且不重新入队，
 *       消息照旧被 DLX 收进死信队列。本类只「加日志」，绝不能变成「吞掉消息」或「重新入队」。</li>
 * </ol>
 *
 * <p>用 Logback 的 {@code ListAppender} 抓真实格式化后的日志文本，而不是断言内部方法 ——
 * 这样能把「占位符与参数个数不匹配」这类只会在运行时暴露的低级错误一并钉死。</p>
 */
class LoggingMessageRecovererTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(LoggingMessageRecoverer.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private static Message message(String payload) {
        MessageProperties props = new MessageProperties();
        // 这几个字段在真实链路上由框架填充，已对 spring-amqp 3.2.0 源码核实：
        //  - consumerQueue ← BlockingQueueConsumer#handleDelivery: setConsumerQueue(delivery.getQueue())
        //  - receivedExchange / receivedRoutingKey / redelivered
        //      ← DefaultMessagePropertiesConverter#toMessageProperties 由 Envelope 填充
        props.setConsumerQueue("zxyz.im.file.events");
        props.setReceivedExchange("zxyz.topic");
        props.setReceivedRoutingKey("file.resource.changed");
        props.setMessageId("msg-1");
        // 真实链路上 DefaultMessagePropertiesConverter 用 envelope.isRedeliver()（原始 boolean）
        // 显式填充，所以这里也显式设 FALSE，而不是留空。
        // 留空 ⇒ 字段是 null（MessageProperties#redelivered 无初值），走的是「护栏」分支，
        // 已由 recover_neverThrowsSecondaryExceptionWhenFieldsAreMissing 单独覆盖。
        props.setRedelivered(Boolean.FALSE);
        props.setHeader("x-first-death-queue", "zxyz.im.file.events");
        props.setHeader("x-first-death-reason", "rejected");
        return new Message(payload.getBytes(StandardCharsets.UTF_8), props);
    }

    private String loggedText() {
        assertEquals(1, appender.list.size(), "重试耗尽应恰好产生一条日志");
        return appender.list.get(0).getFormattedMessage();
    }

    @Test
    void recover_logsErrorWithFullContextAndStillDeadLetters() {
        IllegalStateException cause = new IllegalStateException("下游依赖不可用");
        Message original = message("{\"a\":1}");

        // 投递语义必须不变：类型与因果链都与容器默认 recoverer 一致。
        ListenerExecutionFailedException thrown = assertThrows(ListenerExecutionFailedException.class,
                () -> new LoggingMessageRecoverer("im-service", 3).recover(original, cause),
                "必须保持「拒绝且不重新入队」，否则消息不会进死信队列");
        assertInstanceOf(AmqpRejectAndDontRequeueException.class, thrown.getCause(),
                "容器靠 cause 是 AmqpRejectAndDontRequeueException 来判定不重新入队");
        assertSame(cause, thrown.getCause().getCause(), "原始异常必须可追到，否则堆栈断链");
        assertSame(original, thrown.getFailedMessage(), "死信携带的必须还是原消息");

        assertEquals(Level.ERROR, appender.list.get(0).getLevel(), "L7 口径要求这一条是 ERROR");

        String text = loggedText();
        assertTrue(text.contains("service=im-service"), text);
        assertTrue(text.contains("queue=zxyz.im.file.events"), text);
        assertTrue(text.contains("exchange=zxyz.topic"), text);
        assertTrue(text.contains("routingKey=file.resource.changed"), text);
        assertTrue(text.contains("messageId=msg-1"), text);
        assertTrue(text.contains("redelivered=false"), text);
        assertTrue(text.contains("retryMaxAttempts=3"), text);
        assertTrue(text.contains("payloadLength=7"), text);
        assertTrue(text.contains("payloadSha256_12=015abd7f5cc5"), text);
        assertTrue(text.contains("payloadPreview={\"a\":1}"), text);
        assertTrue(text.contains("firstQueue=zxyz.im.file.events"), text);
        assertTrue(text.contains("firstReason=rejected"), text);
        assertTrue(text.contains("causeType=IllegalStateException"), text);
        assertTrue(text.contains("causeMessage=下游依赖不可用"), text);
    }

    @Test
    void recover_neverThrowsSecondaryExceptionWhenFieldsAreMissing() {
        // 框架理论上一定填好这些字段，但诊断代码不能假设：缺字段时降级为 n/a，而不是再抛一次。
        Message bare = new Message("x".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        ListenerExecutionFailedException thrown = assertThrows(ListenerExecutionFailedException.class,
                () -> new LoggingMessageRecoverer("file-service", 3).recover(bare, new RuntimeException("boom")));
        assertEquals("boom", thrown.getCause().getCause().getMessage());

        String text = loggedText();
        assertTrue(text.contains("service=file-service"), text);
        assertTrue(text.contains("queue=n/a"), text);
        assertTrue(text.contains("messageId=n/a"), text);
        // MessageProperties#redelivered 无初值 ⇒ isRedelivered() 返回 null（装箱 Boolean）。
        // 曾经写成 props.isRedelivered() 直接自动拆箱 ⇒ 在故障现场抛 NPE，
        // 把「消息为什么死信」这个真正的问题盖掉。这里钉死它必须降级而不是抛。
        assertTrue(text.contains("redelivered=n/a"), text);
        assertTrue(text.contains("death=none"), text);
    }

    @Test
    void recover_handlesNullCauseAndHonoursConfiguredMaxAttempts() {
        assertThrows(ListenerExecutionFailedException.class,
                () -> new LoggingMessageRecoverer("share-service", 5).recover(message("{}"), null));

        String text = loggedText();
        assertTrue(text.contains("causeType=n/a"), text);
        assertTrue(text.contains("causeMessage="), text);
        assertTrue(text.contains("retryMaxAttempts=5"), text);
    }
}
