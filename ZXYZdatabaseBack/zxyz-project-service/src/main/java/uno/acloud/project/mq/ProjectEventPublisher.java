package uno.acloud.project.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.mq.MqRetryTemplateFactory;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.exception.BusinessException;

import java.util.HashMap;
import java.util.Map;

/**
 * 发布项目领域事件到 RabbitMQ。
 * 当项目成员变更时通知下游服务（如 file-service）刷新访问缓存。
 */
@Slf4j
@Component
public class ProjectEventPublisher {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    public ProjectEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.retryTemplate = MqRetryTemplateFactory.createDefault(getClass().getName());
    }

    public void publishMemberAdded(Long projectId, Long userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        payload.put("userId", userId);
        publish(RabbitMqConstants.ROUTING_KEY_PROJECT_MEMBER_ADDED, payload);
    }

    public void publishMemberRemoved(Long projectId, Long userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        payload.put("userId", userId);
        publish(RabbitMqConstants.ROUTING_KEY_PROJECT_MEMBER_REMOVED, payload);
    }

    private void publish(String routingKey, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "序列化项目事件失败: " + routingKey);
        }
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, routingKey, json);
                log.debug("发布项目事件到 RabbitMQ: routingKey={}", routingKey);
                return null;
            });
        } catch (Exception e) {
            log.error("发布项目事件失败（已重试{}次）: routingKey={}", MAX_RETRY_ATTEMPTS, routingKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MQ事件发布失败: " + routingKey);
        }
    }
}
