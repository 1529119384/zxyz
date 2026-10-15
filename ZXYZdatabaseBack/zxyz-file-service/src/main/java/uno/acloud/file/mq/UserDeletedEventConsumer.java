package uno.acloud.file.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.mq.MqUserDeletedEventConsumerTemplate;
import uno.acloud.file.config.RabbitMqConfig;
import uno.acloud.file.service.FileUserCleanupService;

/**
 * 消费用户注销/删除事件，委托给 FileUserCleanupService 执行清理。
 *
 * <p>消费骨架（事件过滤 + 幂等认领 + try-catch + 日志口径 + 毒消息进 DLQ）已收敛到
 * {@link MqUserDeletedEventConsumerTemplate}（60 号审计 🟡-5 第 1 组），本类只保留
 * 队列声明与清理动作委托；file 版此前实现的 F4 三件套语义已随 🟠-4 下沉 common，
 * 四处消费端共用同一实现。</p>
 */
@Component
public class UserDeletedEventConsumer {

    private final MqUserDeletedEventConsumerTemplate template;

    public UserDeletedEventConsumer(ObjectMapper objectMapper, FileUserCleanupService cleanupService) {
        this.template = new MqUserDeletedEventConsumerTemplate(objectMapper, cleanupService);
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        template.handleUserEvent(message);
    }
}
