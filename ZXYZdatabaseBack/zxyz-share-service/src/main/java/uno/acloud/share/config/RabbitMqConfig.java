package uno.acloud.share.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.RabbitMqConstants;

/**
 * share-service 的 RabbitMQ 配置。
 * <p>监听用户删除事件（user.deleted），用于清理用户创建的分享。</p>
 */
@Configuration
public class RabbitMqConfig {

    public static final String QUEUE_USER_EVENTS = "zxyz.share.user-events";

    /** 分享专用用户资料更新队列，独立绑定 user.profile.updated 路由键，避免与删除事件消费者竞争同一条消息 */
    public static final String QUEUE_SHARE_USER_PROFILE_UPDATED = "zxyz.share.user.profile.updated";

    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String DLX_EXCHANGE = RabbitMqConstants.DLX_EXCHANGE;
    private static final String DLQ_QUEUE = "zxyz.share.dlq";
    private static final long MESSAGE_TTL = 86400000L;

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(RabbitMqConstants.EXCHANGE);
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue userEventsQueue() {
        return QueueBuilder.durable(QUEUE_USER_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding userEventsBinding(Queue userEventsQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(userEventsQueue).to(topicExchange)
                .with(RabbitMqConstants.ROUTING_KEY_USER_DELETED);
    }

    @Bean
    public Queue userProfileUpdatedQueue() {
        return QueueBuilder.durable(QUEUE_SHARE_USER_PROFILE_UPDATED)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding userProfileUpdatedBinding(Queue userProfileUpdatedQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(userProfileUpdatedQueue).to(topicExchange)
                .with(RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED);
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
