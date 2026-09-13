package uno.acloud.common.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uno.acloud.common.mq.LoggingMessageRecoverer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqRecovererAutoConfiguration} 单元测试。
 *
 * <p>这里钉的是一个「静默失效」型契约 —— 它坏了不会有任何报错，只会让审计 L7 的修复
 * 悄悄消失：</p>
 * <ul>
 *   <li>默认必须<strong>恰好</strong>提供 1 个 {@link MessageRecoverer}。Spring Boot 用
 *       {@code ObjectProvider#getIfUnique()} 取 recoverer，<strong>0 个或 ≥2 个都会被整体放弃</strong>，
 *       静默退回默认实现（那时又只剩一条不带上下文的 WARN）。</li>
 *   <li>业务侧自行定义 recoverer 时必须退让（不能出现第二个）。</li>
 *   <li>{@code @Value} 的两个注入值必须真的到达 recoverer（服务名与重试上限）。</li>
 * </ul>
 */
class MqRecovererAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MqRecovererAutoConfiguration.class));

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

    private static Message deadLetterCandidate() {
        MessageProperties props = new MessageProperties();
        props.setConsumerQueue("zxyz.team.events");
        return new Message("{}".getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void registersExactlyOneLoggingRecovererByDefault() {
        runner.run(context -> {
            assertEquals(1, context.getBeansOfType(MessageRecoverer.class).size(),
                    "必须恰好一个 —— 0 个或 ≥2 个都会被 Boot 的 getIfUnique() 放弃");
            assertInstanceOf(LoggingMessageRecoverer.class, context.getBean(MessageRecoverer.class));
        });
    }

    @Test
    void backsOffWhenBusinessDefinesItsOwnRecoverer() {
        MessageRecoverer custom = (message, cause) -> {
            // 业务自定义实现：本自动配置必须让位。
        };
        runner.withBean("customRecoverer", MessageRecoverer.class, () -> custom).run(context -> {
            assertSame(custom, context.getBean(MessageRecoverer.class), "业务定义优先");
            assertEquals(1, context.getBeansOfType(MessageRecoverer.class).size(),
                    "两个 recoverer 会让 Boot 判定不唯一而整体放弃，必须仍然只有一个");
        });
    }

    @Test
    void bindsServiceNameAndRetryMaxAttemptsFromEnvironment() {
        runner.withPropertyValues(
                        "spring.application.name=user-service",
                        "spring.rabbitmq.listener.simple.retry.max-attempts=7")
                .run(context -> {
                    MessageRecoverer recoverer = context.getBean(MessageRecoverer.class);
                    assertThrows(RuntimeException.class,
                            () -> recoverer.recover(deadLetterCandidate(), new IllegalStateException("db down")));

                    assertEquals(1, appender.list.size());
                    String text = appender.list.get(0).getFormattedMessage();
                    assertTrue(text.contains("service=user-service"), text);
                    assertTrue(text.contains("retryMaxAttempts=7"), text);
                    assertTrue(text.contains("queue=zxyz.team.events"), text);
                });
    }
}
