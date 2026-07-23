package uno.acloud.file.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.RabbitMqConstants;

/**
 * file-service 的 RabbitMQ 配置。
 * <p>监听项目成员变更事件（project.member.added / project.member.removed），
 * 用于失效项目访问缓存。</p>
 * <p>监听用户删除事件（user.deleted），用于清理用户个人空间文件。</p>
 */
@Configuration
public class RabbitMqConfig {

    public static final String QUEUE_PROJECT_MEMBER_EVENTS = "zxyz.file.project-member-events";
    public static final String QUEUE_USER_EVENTS = "zxyz.file.user-events";

    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String DLX_EXCHANGE = RabbitMqConstants.DLX_EXCHANGE;
    private static final String DLQ_QUEUE = "zxyz.file.dlq";
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
    public Queue projectMemberEventsQueue() {
        return QueueBuilder.durable(QUEUE_PROJECT_MEMBER_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding projectMemberEventsBinding(Queue projectMemberEventsQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(projectMemberEventsQueue).to(topicExchange)
                .with("project.member.*");
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
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with("#");
    }
}
