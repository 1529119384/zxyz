package uno.acloud.project.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uno.acloud.common.RabbitMqConstants;

/**
 * project-service 的 RabbitMQ 配置。
 * <p>监听文件资源变更事件（file.resource.changed），用于失效存储用量缓存。
 * 监听团队成员变更事件（team.member.*），用于失效用户团队列表缓存。</p>
 */
@Configuration
public class RabbitMqConfig {

    public static final String QUEUE_FILE_EVENTS = "zxyz.project.file-events";
    public static final String QUEUE_TEAM_MEMBER_EVENTS = "zxyz.project.team-member-events";

    private static final String X_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";
    private static final String DLX_EXCHANGE = RabbitMqConstants.DLX_EXCHANGE;
    private static final String DLQ_QUEUE = "zxyz.project.dlq";
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
    public Queue fileEventsQueue() {
        return QueueBuilder.durable(QUEUE_FILE_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding fileEventsBinding(Queue fileEventsQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(fileEventsQueue).to(topicExchange)
                .with(RabbitMqConstants.ROUTING_KEY_FILE_RESOURCE_CHANGED);
    }

    @Bean
    public Queue teamMemberEventsQueue() {
        return QueueBuilder.durable(QUEUE_TEAM_MEMBER_EVENTS)
                .withArgument(X_DEAD_LETTER_EXCHANGE, DLX_EXCHANGE)
                .withArgument("x-message-ttl", MESSAGE_TTL)
                .build();
    }

    @Bean
    public Binding teamMemberAddedBinding(Queue teamMemberEventsQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(teamMemberEventsQueue).to(topicExchange)
                .with(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED);
    }

    @Bean
    public Binding teamMemberRemovedBinding(Queue teamMemberEventsQueue, TopicExchange topicExchange) {
        return BindingBuilder.bind(teamMemberEventsQueue).to(topicExchange)
                .with(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED);
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
