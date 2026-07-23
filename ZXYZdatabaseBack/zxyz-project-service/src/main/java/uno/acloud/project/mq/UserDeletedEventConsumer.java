package uno.acloud.project.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.project.config.RabbitMqConfig;
import uno.acloud.project.mapper.ProjectMapper;

import java.util.concurrent.TimeUnit;

/**
 * 消费用户注销/删除事件，移除用户项目成员关系。
 *
 * <p>project-service 监听 user.deleted 路由键，删除用户在 project_member 表中的记录。</p>
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final ProjectMapper projectMapper;
    private final StringRedisTemplate redisTemplate;

    public UserDeletedEventConsumer(ObjectMapper objectMapper,
                                    ProjectMapper projectMapper,
                                    StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.projectMapper = projectMapper;
        this.redisTemplate = redisTemplate;
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

            // 幂等性检查
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + userId;
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户删除事件，跳过处理: key={}", idempotencyKey);
                return;
            }

            log.info("MQ: 开始移除用户项目成员关系: userId={}, username={}", userId, username);
            removeUserFromProjects(userId);
            log.info("MQ: 用户项目成员关系移除完成: userId={}, username={}", userId, username);
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }

    /**
     * 从所有项目中移除用户成员关系。
     */
    @Transactional(rollbackFor = Exception.class)
    protected void removeUserFromProjects(long userId) {
        int deleted = projectMapper.deleteByUserId(userId);
        log.info("移除用户项目成员关系完成: userId={}, removedCount={}", userId, deleted);
    }
}
