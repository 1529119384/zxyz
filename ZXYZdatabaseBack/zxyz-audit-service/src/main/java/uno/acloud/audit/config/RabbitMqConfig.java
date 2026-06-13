package uno.acloud.audit.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.RabbitMqConstants;

@Configuration
public class RabbitMqConfig {

    public static final String QUEUE_AUDIT_OPERATE_LOG = "zxyz.audit-operate-log";

    public static final String DLX_EXCHANGE = RabbitMqConstants.DLX_EXCHANGE;
    public static final String DLQ_QUEUE = "zxyz.audit.dlq";

    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final long MESSAGE_TTL = 86400000L;

    @Bean
    public TopicExchange auditExchange() {
        return new TopicExchange(RabbitMqConstants.EXCHANGE);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue auditOperateLogQueue() {
        return QueueBuilder.durable(QUEUE_AUDIT_OPERATE_LOG)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding auditOperateLogBinding(Queue auditOperateLogQueue, TopicExchange auditExchange) {
        return BindingBuilder.bind(auditOperateLogQueue).to(auditExchange).with(RabbitMqConstants.ROUTING_KEY_AUDIT_LOG);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }
}
