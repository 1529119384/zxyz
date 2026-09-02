package uno.acloud.share.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserProfileUpdatedEvent;
import uno.acloud.share.config.RabbitMqConfig;
import uno.acloud.share.service.ShareUserProfileSyncService;

import java.util.concurrent.TimeUnit;

/**
 * 消费用户资料更新事件，同步分享表中冗余的用户名。
 *
 * <p>反序列化为 {@link UserProfileUpdatedEvent} 结构化事件，
 * 参照 im-service 的 UserEventConsumer 做 Redis 幂等处理。
 * 监听独立的分享专用队列，避免与用户删除事件消费者竞争同一条消息。</p>
 */
@Slf4j
@Component
public class UserProfileUpdatedEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:profile:";
    private static final long IDEMPOTENCY_TTL_HOURS = 1;

    private final ObjectMapper objectMapper;
    private final ShareUserProfileSyncService userProfileSyncService;
    private final StringRedisTemplate redisTemplate;

    public UserProfileUpdatedEventConsumer(ObjectMapper objectMapper,
                                           ShareUserProfileSyncService userProfileSyncService,
                                           StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.userProfileSyncService = userProfileSyncService;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_SHARE_USER_PROFILE_UPDATED)
    public void handleUserProfileUpdated(String message) {
        try {
            UserProfileUpdatedEvent event = objectMapper.readValue(message, UserProfileUpdatedEvent.class);
            String eventType = event.eventType();
            if (!RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED.equals(eventType)) {
                log.debug("MQ: 忽略非 user.profile.updated 事件: eventType={}", eventType);
                return;
            }

            // 幂等性检查：以 userId 作为去重 key，防止重复消费
            long userId = event.userId();
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + userId;
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户资料更新事件，跳过处理: key={}", idempotencyKey);
                return;
            }

            try {
                log.info("MQ: 开始同步用户分享用户名: userId={}, username={}", userId, event.username());
                userProfileSyncService.syncUsername(userId, event.username());
                log.info("MQ: 用户分享用户名同步完成: userId={}", userId);
            } catch (Exception e) {
                // 处理失败释放幂等 key，允许后续重试
                redisTemplate.delete(idempotencyKey);
                log.error("处理用户资料更新事件 RabbitMQ 消息失败（将重试）, userId={}, message={}", userId, message, e);
                throw new RuntimeException("处理用户资料更新事件消息失败", e);
            }
        } catch (JsonProcessingException e) {
            log.error("用户资料更新事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户资料更新事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户资料更新事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户资料更新事件消息失败", e);
        }
    }
}
