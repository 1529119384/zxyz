package uno.acloud.audit.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uno.acloud.common.RabbitMqConstants;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RabbitMqConfig} 是"毒消息不丢"的基建契约：
 * 主队列必须带上 {@code x-dead-letter-exchange} 与 TTL，DLQ 必须被 {@code #} 兜住，
 * 否则消费失败的消息会被静默丢弃 —— 审计日志恰恰是最不能丢数据的地方。
 * 这里把契约钉在测试里，避免有人"顺手简化"掉队列参数。
 */
class RabbitMqConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitMqConfig.class);

    @Test
    void declaresAuditExchangeOnSharedExchangeName() {
        runner.run(ctx -> assertThat(ctx.getBean("auditExchange", TopicExchange.class).getName())
                .isEqualTo(RabbitMqConstants.EXCHANGE));
    }

    @Test
    void auditQueue_isDurableAndDeadLettersToDlxWithTtl() {
        runner.run(ctx -> {
            Queue queue = ctx.getBean("auditOperateLogQueue", Queue.class);

            assertThat(queue.getName()).isEqualTo(RabbitMqConfig.QUEUE_AUDIT_OPERATE_LOG);
            assertThat(queue.isDurable()).isTrue();
            assertThat(queue.getArguments())
                    .containsEntry("x-dead-letter-exchange", RabbitMqConfig.DLX_EXCHANGE)
                    .containsEntry("x-message-ttl", 86400000L);
        });
    }

    @Test
    void auditBinding_usesTheSharedRoutingKey() {
        runner.run(ctx -> {
            Binding binding = ctx.getBean("auditOperateLogBinding", Binding.class);

            assertThat(binding.getExchange()).isEqualTo(RabbitMqConstants.EXCHANGE);
            assertThat(binding.getDestination()).isEqualTo(RabbitMqConfig.QUEUE_AUDIT_OPERATE_LOG);
            assertThat(binding.getRoutingKey()).isEqualTo(RabbitMqConstants.ROUTING_KEY_AUDIT_LOG);
        });
    }

    @Test
    void deadLetterQueueAndBinding_catchEverythingOnDlx() {
        runner.run(ctx -> {
            Queue dlq = ctx.getBean("deadLetterQueue", Queue.class);
            assertThat(dlq.getName()).isEqualTo(RabbitMqConfig.DLQ_QUEUE);
            assertThat(dlq.isDurable()).isTrue();

            assertThat(ctx.getBean("deadLetterExchange", TopicExchange.class).getName())
                    .isEqualTo(RabbitMqConfig.DLX_EXCHANGE);

            Binding binding = ctx.getBean("deadLetterBinding", Binding.class);
            assertThat(binding.getExchange()).isEqualTo(RabbitMqConfig.DLX_EXCHANGE);
            assertThat(binding.getDestination()).isEqualTo(RabbitMqConfig.DLQ_QUEUE);
            // "#" 兜住 DLX 上的一切路由键，保证任何死信都有队列可落
            assertThat(binding.getRoutingKey()).isEqualTo("#");
        });
    }

    @Test
    void dlqNameMatchesTheQueueDeclaredByAuditDlqConsumer() {
        runner.run(ctx -> assertThat(ctx.getBean("deadLetterQueue", Queue.class).getName())
                .isEqualTo(RabbitMqConfig.DLQ_QUEUE));
    }
}
