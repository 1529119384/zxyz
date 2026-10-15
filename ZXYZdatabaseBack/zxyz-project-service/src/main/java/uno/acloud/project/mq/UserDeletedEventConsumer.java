package uno.acloud.project.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.mq.MqUserDeletedEventConsumerTemplate;
import uno.acloud.project.config.RabbitMqConfig;
import uno.acloud.project.service.ProjectUserCleanupService;

/**
 * 消费用户注销/删除事件，委托给 ProjectUserCleanupService 执行清理。
 *
 * <p>消费骨架（事件过滤 + 幂等认领 + try-catch + 日志口径 + 毒消息进 DLQ）已收敛到
 * {@link MqUserDeletedEventConsumerTemplate}（60 号审计 🟡-5 第 1 组），本类只保留
 * 队列声明与清理动作委托；幂等三件套语义随 🟠-4 一并统一到 common。</p>
 */
@Component
public class UserDeletedEventConsumer {

    private final MqUserDeletedEventConsumerTemplate template;

    public UserDeletedEventConsumer(ObjectMapper objectMapper, ProjectUserCleanupService cleanupService) {
        this.template = new MqUserDeletedEventConsumerTemplate(objectMapper, cleanupService);
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        template.handleUserEvent(message);
    }
}
