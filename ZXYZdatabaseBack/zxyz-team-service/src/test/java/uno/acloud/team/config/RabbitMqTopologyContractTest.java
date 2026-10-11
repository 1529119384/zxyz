package uno.acloud.team.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Binding.DestinationType;
import uno.acloud.common.RabbitMqConstants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 54 号 §2.3 C3：team 的 RabbitMQ 拓扑契约测试。
 *
 * <p>exchange / queue / binding / DLQ 绑定全部从 {@link RabbitMqConfig} 的源码 Bean
 * 声明解析断言，<b>不连接 RabbitMQ</b> —— 拓扑漂移（队列名改写、DLX 参数丢失、
 * 路由键错拼）在构建期即变红，而不是等到生产消费端收不到消息。</p>
 *
 * <p>契约依据：</p>
 * <ul>
 *   <li>消费端 {@code @RabbitListener} 只监听 {@code RabbitMqConfig.QUEUE_USER_EVENTS}
 *       （UserDeletedEventConsumer，唯一一处），队列名必须与监听常量一致；</li>
 *   <li>user.deleted 事件由 user-service 发布到 {@code zxyz.topic}，team 绑定该路由键接收；</li>
 *   <li>毒消息规范：业务队列必须挂 DLX（{@code x-dead-letter-exchange}），
 *       消费端抛 {@code AmqpRejectAndDontRequeueException} 时消息进 zxyz.dlx → zxyz.team.dlq。</li>
 * </ul>
 */
class RabbitMqTopologyContractTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    // ==================== exchange ====================

    @Test
    void topicExchange_mustBeCommonTopicExchange_zxyzTopic() {
        TopicExchange exchange = config.topicExchange();
        assertNotNull(exchange);
        assertEquals(RabbitMqConstants.EXCHANGE, exchange.getName(),
                "业务 exchange 必须是 common 常量 zxyz.topic，各服务靠它互通");
        assertTrue(exchange.isDurable(), "exchange 必须 durable（与生产声明一致）");
    }

    @Test
    void deadLetterExchange_mustBeCommonDlx_zxyzDlx() {
        TopicExchange dlx = config.deadLetterExchange();
        assertNotNull(dlx);
        assertEquals(RabbitMqConstants.DLX_EXCHANGE, dlx.getName(),
                "DLX 必须是 common 常量 zxyz.dlx");
        assertTrue(dlx.isDurable());
    }

    // ==================== queue ====================

    @Test
    void userEventsQueue_namedAfterListenerConstant_withDeadLetterArguments() {
        Queue queue = config.userEventsQueue();
        assertNotNull(queue);
        // 队列名必须与 @RabbitListener 引用的常量逐字一致，否则启动即 Listener 监听不存在的队列
        assertEquals(RabbitMqConfig.QUEUE_USER_EVENTS, queue.getName());
        assertEquals("zxyz.team.user-events", queue.getName(), "队列名契约：消费端监听与拓扑声明同源");
        assertTrue(queue.isDurable());

        // 毒消息规范：业务队列必须挂 DLX（消费端 AmqpRejectAndDontRequeueException 落 DLQ 的前提）
        Object dlxArg = queue.getArguments().get("x-dead-letter-exchange");
        assertEquals(RabbitMqConstants.DLX_EXCHANGE, dlxArg,
                "业务队列必须声明 x-dead-letter-exchange=zxyz.dlx，否则毒消息会无限重投");
        Object ttlArg = queue.getArguments().get("x-message-ttl");
        assertEquals(86400000L, ttlArg, "未处理消息 24h TTL（防死信/重投积压无限膨胀）");
    }

    @Test
    void deadLetterQueue_namedAfterDlqConstant() {
        Queue dlq = config.deadLetterQueue();
        assertNotNull(dlq);
        assertEquals("zxyz.team.dlq", dlq.getName());
        assertTrue(dlq.isDurable());
    }

    // ==================== binding ====================

    @Test
    void userEventsBinding_bindsUserDeletedRoutingKeyOnTopicExchange() {
        Binding binding = config.userEventsBinding(config.userEventsQueue(), config.topicExchange());
        assertNotNull(binding);
        assertEquals(RabbitMqConfig.QUEUE_USER_EVENTS, binding.getDestination(),
                "绑定目标必须是 user-events 队列");
        assertEquals(DestinationType.QUEUE, binding.getDestinationType());
        assertEquals(RabbitMqConstants.EXCHANGE, binding.getExchange(),
                "业务路由必须走 zxyz.topic");
        assertEquals(RabbitMqConstants.ROUTING_KEY_USER_DELETED, binding.getRoutingKey(),
                "team 只消费 user.deleted 路由键（UserDeletedEventConsumer 的唯一事件源）");
    }

    @Test
    void deadLetterBinding_catchesAllRoutingKeysIntoTeamDlq() {
        Binding binding = config.deadLetterBinding(config.deadLetterQueue(), config.deadLetterExchange());
        assertNotNull(binding);
        assertEquals("zxyz.team.dlq", binding.getDestination());
        assertEquals(DestinationType.QUEUE, binding.getDestinationType());
        assertEquals(RabbitMqConstants.DLX_EXCHANGE, binding.getExchange());
        assertEquals("#", binding.getRoutingKey(),
                "DLQ 绑定必须是 # 通配，保证 zxyz.dlx 上任何死信都能落到 team 的 DLQ");
    }

    // ==================== 常量与 common 事件定义同源 ====================

    @Test
    void consumedRoutingKey_matchesUserDeletedEventDefaultEventType() {
        // 消费端按事件体里的 eventType 过滤（非路由键），两者必须同源 —— 防止改一处漏一处
        assertEquals(RabbitMqConstants.ROUTING_KEY_USER_DELETED,
                uno.acloud.common.event.UserDeletedEvent.of(1L, "u").eventType());
    }
}
