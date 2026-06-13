package uno.acloud.im.config;

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

    public static final String QUEUE = "zxyz.im.file-events";

    public static final String QUEUE_TEAM_EVENTS = "zxyz.im.team-events";
    public static final String ROUTING_KEY_TEAM = "team.#";

    public static final String QUEUE_USER_EVENTS = "zxyz.im.user-events";
    public static final String ROUTING_KEY_USER = "user.#";

    public static final String DLX_EXCHANGE = RabbitMqConstants.DLX_EXCHANGE;
    public static final String DLQ_QUEUE = "zxyz.im.dlq";

    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final long MESSAGE_TTL = 86400000L;

    @Bean
    public TopicExchange fileResourceExchange() {
        return new TopicExchange(RabbitMqConstants.EXCHANGE);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue fileEventsQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding fileEventsBinding(Queue fileEventsQueue, TopicExchange fileResourceExchange) {
        return BindingBuilder.bind(fileEventsQueue).to(fileResourceExchange).with(RabbitMqConstants.ROUTING_KEY_FILE_RESOURCE_CHANGED);
    }

    @Bean
    public Queue teamEventsQueue() {
        return QueueBuilder.durable(QUEUE_TEAM_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding teamEventsBinding(Queue teamEventsQueue, TopicExchange fileResourceExchange) {
        return BindingBuilder.bind(teamEventsQueue).to(fileResourceExchange).with(ROUTING_KEY_TEAM);
    }

    @Bean
    public Queue userEventsQueue() {
        return QueueBuilder.durable(QUEUE_USER_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding userEventsBinding(Queue userEventsQueue, TopicExchange fileResourceExchange) {
        return BindingBuilder.bind(userEventsQueue).to(fileResourceExchange).with(ROUTING_KEY_USER);
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
