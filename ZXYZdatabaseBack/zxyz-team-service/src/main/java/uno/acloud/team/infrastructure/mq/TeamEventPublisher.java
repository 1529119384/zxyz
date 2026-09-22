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
 * <p><b>m52：成员事件的处理顺序由消费端按事件里的 {@code sequenceNumber} 判定，
 * 与 RabbitMQ 消息优先级无关。</b></p>
 *
 * <p>此前这里用 {@code MessageProperties.setPriority(1/2)} 声称「保证 added 先于 removed 处理」，
 * 但全仓 <b>没有任何队列声明 {@code x-max-priority}</b>，未声明的队列会直接忽略消息优先级
 * ⇒ 该保证<b>静默失效</b>，是个名不副实的承诺。现已移除 priority 设置：
 * 真正生效的机制一直是 {@code sequenceNumber}（消费方据此检测乱序并决定丢弃）。</p>
 *
 * <p>⚠️ 不要试图给已存在的生产队列补 {@code x-max-priority}：改队列参数会触发
 * {@code PRECONDITION_FAILED}，需要先删除队列，风险远大于收益。</p>
 *
 * <p>使用 {@code uno.acloud.common.event} 包下的结构化事件 record 替代 HashMap，
 * 保证字段类型安全和事件格式一致性。</p>
 */
@Slf4j
@Component
public class TeamEventPublisher {

    /**
     * m52: 消息序列号，用于消费方检测乱序。
     *
     * <p>以毫秒时间戳 ×1000 为种子：进程内自增保证同毫秒不重复，跨重启仍保持单调递增。
     * 此前是 0 起始的纯自增计数器，team-service 每次重启后序号都从 1 重新开始，
     * 而消费方按「序号回退 = 乱序」判据会把它们全部丢弃（且序列号 key 有 24h TTL），
     * 于是重启后 24 小时内所有成员变更事件静默不生效。</p>
     */
    private final AtomicLong sequenceCounter = new AtomicLong(System.currentTimeMillis() * 1000L);

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
        // 顺序由 event 里的 sequenceNumber 判定（消费端据此检测乱序），不依赖 MQ 优先级。
        publish(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED, event);
    }

    public void publishMemberRemoved(Long teamId, Long userId) {
        long seq = sequenceCounter.incrementAndGet();
        TeamMemberRemovedEvent event = TeamMemberRemovedEvent.of(teamId, userId, seq);
        // 顺序由 event 里的 sequenceNumber 判定（消费端据此检测乱序），不依赖 MQ 优先级。
        publish(RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED, event);
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
