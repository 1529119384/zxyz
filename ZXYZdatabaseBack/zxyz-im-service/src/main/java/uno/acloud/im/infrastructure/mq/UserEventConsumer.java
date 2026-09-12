package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
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
            UserProfileUpdatedEvent baseEvent = objectMapper.readValue(message, UserProfileUpdatedEvent.class);
            String eventType = baseEvent.eventType();
            if (eventType == null || eventType.isEmpty()) {
                log.warn("MQ: 用户事件消息缺少 eventType 字段，丢弃消息: {}", message);
                return;
            }

            // 幂等性检查：eventType + userId + 事件时间戳。
            // 只用 eventType + userId 建 key 时，同一用户在 TTL（1h）内的第二次资料变更
            // 会被误判为重复投递而静默丢弃（im 侧资料永久停在旧值）。
            // 事件自带毫秒时间戳且经 record 紧凑构造器保证非 0，能唯一标识一次事件；
            // 而真正的重投（同一条消息）时间戳不变，仍会被正确拦下。
            long userId = baseEvent.userId();
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + eventType + ":" + userId + ":" + baseEvent.timestamp();
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户事件消息，跳过处理: key={}", idempotencyKey);
                return;
            }

            try {
                if (RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED.equals(eventType)) {
                    UserProfileUpdatedEvent event = objectMapper.readValue(message, UserProfileUpdatedEvent.class);
                    InternalUserProfileSyncRequest request = toSyncRequest(event);
                    userProfileSyncService.syncUserProfile(request);
                    log.debug("MQ: 用户资料同步完成: userId={}", userId);
                } else if (RabbitMqConstants.ROUTING_KEY_USER_DELETED.equals(eventType)) {
                    UserDeletedEvent deletedEvent = objectMapper.readValue(message, UserDeletedEvent.class);
                    userProfileSyncService.removeUserProfile(deletedEvent.userId());
                    log.info("MQ: 用户资料删除同步完成: userId={}", deletedEvent.userId());
                } else {
                    log.debug("MQ: 未知用户事件类型: {}", eventType);
                }
            } catch (Exception e) {
                // 处理失败释放幂等占位键，否则重投会被判为「重复消息」而静默丢弃（数据永久分歧）。
                releaseIdempotencyKey(idempotencyKey);
                throw e;
            }
        } catch (JsonProcessingException e) {
            log.error("用户事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户事件消息失败", e);
        }
    }

    /**
     * 释放幂等占位键，使失败消息在 MQ 重投时能被真正重新处理。
     * Redis 自身异常只记日志，不得掩盖原始业务异常。
     */
    private void releaseIdempotencyKey(String idempotencyKey) {
        try {
            redisTemplate.delete(idempotencyKey);
            log.warn("MQ: 用户事件处理失败，已释放幂等占位键以便重投重试: key={}", idempotencyKey);
        } catch (Exception e) {
            log.error("MQ: 释放幂等占位键失败（该消息重投将被判为重复而跳过）: key={}", idempotencyKey, e);
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
