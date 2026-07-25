package uno.acloud.team.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.team.config.RabbitMqConfig;
import uno.acloud.team.service.TeamUserCleanupService;

/**
 * 消费用户注销/删除事件，委托给 TeamUserCleanupService 执行清理。
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private final ObjectMapper objectMapper;
    private final TeamUserCleanupService cleanupService;

    public UserDeletedEventConsumer(ObjectMapper objectMapper, TeamUserCleanupService cleanupService) {
        this.objectMapper = objectMapper;
        this.cleanupService = cleanupService;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        try {
            UserDeletedEvent event = objectMapper.readValue(message, UserDeletedEvent.class);
            String eventType = event.eventType();
            if (!RabbitMqConstants.ROUTING_KEY_USER_DELETED.equals(eventType)) {
                log.debug("MQ: 忽略非 user.deleted 事件: eventType={}", eventType);
                return;
            }

            long userId = event.userId();
            String username = event.username();

            if (!cleanupService.tryAcquireIdempotencyKey(userId)) {
                log.warn("MQ: 重复用户删除事件，跳过处理: userId={}", userId);
                return;
            }

            try {
                log.info("MQ: 开始移除用户团队成员关系: userId={}, username={}", userId, username);
                cleanupService.removeUserFromTeams(userId);
                log.info("MQ: 用户团队成员关系移除完成: userId={}, username={}", userId, username);
            } catch (Exception e) {
                cleanupService.releaseIdempotencyKey(userId);
                log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, userId={}, message={}", userId, message, e);
                throw new RuntimeException("处理用户删除事件消息失败", e);
            }
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }
}
