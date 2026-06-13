package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserProfileUpdatedEvent;
import uno.acloud.im.application.InternalUserProfileSyncService;
import uno.acloud.im.config.RabbitMqConfig;
import uno.acloud.im.dto.InternalUserProfileSyncRequest;

import java.util.concurrent.TimeUnit;

/**
 * 消费用户域事件。
 *
 * <p>反序列化为 {@link UserProfileUpdatedEvent} 结构化事件 record，
 * 替代旧的 JsonNode 手动解析方式。</p>
 */
@Slf4j
@Component
public class UserEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:";
    private static final long IDEMPOTENCY_TTL_HOURS = 1;

    private final ObjectMapper objectMapper;
    private final InternalUserProfileSyncService userProfileSyncService;
    private final StringRedisTemplate redisTemplate;

    public UserEventConsumer(ObjectMapper objectMapper,
                             InternalUserProfileSyncService userProfileSyncService,
                             StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.userProfileSyncService = userProfileSyncService;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        try {
            UserProfileUpdatedEvent event = objectMapper.readValue(message, UserProfileUpdatedEvent.class);
            String eventType = event.eventType();
            if (eventType == null || eventType.isEmpty()) {
                log.warn("MQ: 用户事件消息缺少 eventType 字段，丢弃消息: {}", message);
                return;
            }

            // 幂等性检查：使用 eventType + userId 作为去重 key，防止重复消费
            long userId = event.userId();
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + eventType + ":" + userId;
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户事件消息，跳过处理: key={}", idempotencyKey);
                return;
            }

            if (RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED.equals(eventType)) {
                InternalUserProfileSyncRequest request = toSyncRequest(event);
                userProfileSyncService.syncUserProfile(request);
                log.debug("MQ: 用户资料同步完成: userId={}", userId);
            } else {
                log.debug("MQ: 未知用户事件类型: {}", eventType);
            }
        } catch (JsonProcessingException e) {
            log.error("用户事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户事件消息失败", e);
        }
    }

    private static InternalUserProfileSyncRequest toSyncRequest(UserProfileUpdatedEvent event) {
        InternalUserProfileSyncRequest request = new InternalUserProfileSyncRequest();
        request.setUserId(event.userId());
        request.setUsername(event.username());
        request.setName(event.name());
        request.setEmail(event.email());
        request.setAvatar(event.avatar());
        return request;
    }
}
