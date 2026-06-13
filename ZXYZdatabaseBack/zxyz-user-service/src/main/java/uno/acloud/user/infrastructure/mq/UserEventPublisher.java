package uno.acloud.user.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.mq.MqRetryTemplateFactory;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserProfileUpdatedEvent;
import uno.acloud.exception.BusinessException;

/**
 * 发布用户域 MQ 事件。
 *
 * <p>使用 {@link UserProfileUpdatedEvent} 结构化事件 record 替代 HashMap，
 * 携带 eventType/version/timestamp 元数据。</p>
 */
@Slf4j
@Component
public class UserEventPublisher {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    public UserEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.retryTemplate = MqRetryTemplateFactory.createDefault(getClass().getName());
    }

    /**
     * 发布用户资料更新事件。
     */
    public void publishProfileUpdated(Long userId, String username, String name, String email, String avatar) {
        UserProfileUpdatedEvent event = UserProfileUpdatedEvent.of(userId, username, name, email, avatar);
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "序列化用户资料更新事件失败: userId=" + userId);
        }
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED, json);
                log.debug("已发布用户资料更新事件: userId={}", userId);
                return null;
            });
        } catch (Exception e) {
            log.error("发布用户资料更新事件失败（已重试{}次）: userId={}", MAX_RETRY_ATTEMPTS, userId, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MQ事件发布失败: " + RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED);
        }
    }
}
