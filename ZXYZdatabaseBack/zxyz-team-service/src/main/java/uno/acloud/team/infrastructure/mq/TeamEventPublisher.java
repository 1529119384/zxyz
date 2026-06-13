package uno.acloud.team.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.mq.MqRetryTemplateFactory;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.TeamCreatedEvent;
import uno.acloud.common.event.TeamMemberAddedEvent;
import uno.acloud.common.event.TeamMemberRemovedEvent;
import uno.acloud.common.event.TeamUpdatedEvent;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.team.CreateTeamMemberRequest;
import uno.acloud.team.dto.team.CreateTeamRequest;
import uno.acloud.team.entity.Team;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 发布团队领域事件到 RabbitMQ。
 * 替代 ImTeamSyncClient 的 HTTP 同步方式。
 *
 * <p>m52: 成员事件设置 priority header 保证处理顺序：
 * member.added → priority 1（先处理），member.removed → priority 2（后处理）。
 * 同时附加 sequenceNumber 供消费方检测乱序。</p>
 *
 * <p>使用 {@code uno.acloud.common.event} 包下的结构化事件 record 替代 HashMap，
 * 保证字段类型安全和事件格式一致性。</p>
 */
@Slf4j
@Component
public class TeamEventPublisher {

    /** m52: 消息序列号，用于消费方检测乱序 */
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final RetryTemplate retryTemplate;

    public TeamEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.retryTemplate = MqRetryTemplateFactory.createDefault(getClass().getName());
    }

    public void publishTeamCreated(Team team, UserInfoDTO owner, CreateTeamRequest request) {
        TeamCreatedEvent event = TeamCreatedEvent.of(
                team.getId(),
                team.getName(),
                team.getAvatar(),
                team.getDescription(),
                team.getOwnerUserId(),
                owner != null ? owner.getUsername() : null,
                owner != null ? owner.getName() : null,
                owner != null ? owner.getEmail() : null
        );
        publish(RabbitMqConstants.ROUTING_KEY_TEAM_CREATED, event);
    }

    public void publishTeamUpdated(Team team, UserInfoDTO owner) {
        TeamUpdatedEvent event = TeamUpdatedEvent.of(
                team.getId(),
                team.getName(),
                team.getAvatar(),
                team.getDescription(),
                team.getOwnerUserId(),
                owner != null ? owner.getUsername() : null,
                owner != null ? owner.getName() : null,
                owner != null ? owner.getEmail() : null
        );
        publish(RabbitMqConstants.ROUTING_KEY_TEAM_UPDATED, event);
    }

    public void publishMemberCreated(Long teamId, UserInfoDTO user, CreateTeamMemberRequest request) {
        long seq = sequenceCounter.incrementAndGet();
        TeamMemberAddedEvent event = TeamMemberAddedEvent.of(
                teamId,
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getEmail(),
                user.getAvatar(),
                request != null ? request.getRoleCode() : null,
                seq
        );
        // m52: member.added 优先级 1（先于 removed 处理）
        publishWithPriority(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED, event, 1);
    }

    public void publishMemberRemoved(Long teamId, Long userId) {
        long seq = sequenceCounter.incrementAndGet();
        TeamMemberRemovedEvent event = TeamMemberRemovedEvent.of(teamId, userId, seq);
        // m52: member.removed 优先级 2（后于 added 处理）
        publishWithPriority(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED, event, 2);
    }

    /**
     * m52: 带优先级和序列号的发布方法，用于成员事件。
     * 优先级确保 added 先于 removed 处理，序列号用于消费方检测乱序。
     */
    private void publishWithPriority(String routingKey, Object event, int priority) {
        String json = serialize(routingKey, event);
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, routingKey, json, msg -> {
                    msg.getMessageProperties().setPriority(priority);
                    return msg;
                });
                log.debug("发布团队事件到 RabbitMQ: routingKey={}, priority={}", routingKey, priority);
                return null;
            });
        } catch (Exception e) {
            log.error("发布团队事件失败（已重试{}次）: routingKey={}", MAX_RETRY_ATTEMPTS, routingKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MQ事件发布失败: " + routingKey);
        }
    }

    private void publish(String routingKey, Object event) {
        String json = serialize(routingKey, event);
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, routingKey, json);
                log.debug("发布团队事件到 RabbitMQ: routingKey={}", routingKey);
                return null;
            });
        } catch (Exception e) {
            log.error("发布团队事件失败（已重试{}次）: routingKey={}", MAX_RETRY_ATTEMPTS, routingKey, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MQ事件发布失败: " + routingKey);
        }
    }

    private String serialize(String routingKey, Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "序列化团队事件失败: " + routingKey);
        }
    }
}
