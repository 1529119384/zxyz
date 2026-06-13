package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.TeamCreatedEvent;
import uno.acloud.common.event.TeamMemberAddedEvent;
import uno.acloud.common.event.TeamMemberRemovedEvent;
import uno.acloud.common.event.TeamUpdatedEvent;
import uno.acloud.im.application.InternalTeamSyncService;
import uno.acloud.im.config.RabbitMqConfig;
import uno.acloud.im.dto.InternalTeamMemberRemovalRequest;
import uno.acloud.im.dto.InternalTeamMemberSyncRequest;
import uno.acloud.im.dto.InternalTeamSyncRequest;

import java.util.concurrent.TimeUnit;

/**
 * 消费团队领域事件。
 *
 * <p>先读取 eventType 字段判断事件类型，再反序列化为对应的结构化事件 record
 * （{@link TeamCreatedEvent}、{@link TeamUpdatedEvent}、{@link TeamMemberAddedEvent}、
 * {@link TeamMemberRemovedEvent}），最后映射为内部 DTO 调用同步服务。</p>
 *
 * <p>保留幂等性检查和序列号乱序检测逻辑。</p>
 */
@Slf4j
@Component
public class TeamEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:team:";
    private static final long IDEMPOTENCY_TTL_HOURS = 1;

    private final ObjectMapper objectMapper;
    private final InternalTeamSyncService internalTeamSyncService;
    private final StringRedisTemplate redisTemplate;

    public TeamEventConsumer(ObjectMapper objectMapper,
                             InternalTeamSyncService internalTeamSyncService,
                             StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.internalTeamSyncService = internalTeamSyncService;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_TEAM_EVENTS)
    public void handleTeamEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asText("");
            if (eventType.isEmpty()) {
                log.warn("MQ: 团队事件消息缺少 eventType 字段，丢弃消息: {}", message);
                return;
            }

            // 幂等性检查：使用 eventType + 实体标识作为去重 key，防止重复消费
            String idempotencyKey = buildIdempotencyKey(eventType, root);
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复团队事件消息，跳过处理: key={}", idempotencyKey);
                return;
            }

            long sequenceNumber = root.path("sequenceNumber").asLong(0);
            long teamId = root.path("teamId").asLong(0);
            if (sequenceNumber > 0 && teamId > 0) {
                String sequenceKey = "mq:sequence:team:" + teamId;
                String lastSequence = redisTemplate.opsForValue().get(sequenceKey);
                if (lastSequence != null && sequenceNumber <= Long.parseLong(lastSequence)) {
                    log.warn("MQ: 团队事件乱序丢弃: teamId={}, receivedSeq={}, lastSeq={}", teamId, sequenceNumber, lastSequence);
                    return;
                }
                redisTemplate.opsForValue().set(sequenceKey, String.valueOf(sequenceNumber), 24, TimeUnit.HOURS);
                log.debug("MQ: 团队事件序列号: eventType={}, seq={}", eventType, sequenceNumber);
            }

            switch (eventType) {
                case RabbitMqConstants.ROUTING_KEY_TEAM_CREATED -> {
                    TeamCreatedEvent event = objectMapper.readValue(message, TeamCreatedEvent.class);
                    InternalTeamSyncRequest request = toTeamSyncRequest(event);
                    internalTeamSyncService.syncTeam(request);
                    log.debug("MQ: 团队创建同步完成: teamId={}", event.teamId());
                }
                case RabbitMqConstants.ROUTING_KEY_TEAM_UPDATED -> {
                    TeamUpdatedEvent event = objectMapper.readValue(message, TeamUpdatedEvent.class);
                    InternalTeamSyncRequest request = toTeamSyncRequest(event);
                    internalTeamSyncService.syncTeamProfile(request);
                    log.debug("MQ: 团队资料同步完成: teamId={}", event.teamId());
                }
                case RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED -> {
                    TeamMemberAddedEvent event = objectMapper.readValue(message, TeamMemberAddedEvent.class);
                    InternalTeamMemberSyncRequest request = toMemberSyncRequest(event);
                    internalTeamSyncService.syncMember(request);
                    log.debug("MQ: 成员加入同步完成: teamId={}, userId={}, seq={}", event.teamId(), event.userId(), sequenceNumber);
                }
                case RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED -> {
                    TeamMemberRemovedEvent event = objectMapper.readValue(message, TeamMemberRemovedEvent.class);
                    InternalTeamMemberRemovalRequest request = toMemberRemovalRequest(event);
                    internalTeamSyncService.removeMember(request);
                    log.debug("MQ: 成员移除同步完成: teamId={}, userId={}, seq={}", event.teamId(), event.userId(), sequenceNumber);
                }
                default -> log.debug("MQ: 未知团队事件类型: {}", eventType);
            }
        } catch (JsonProcessingException e) {
            log.error("团队事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("团队事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理团队事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理团队事件消息失败", e);
        }
    }

    /**
     * 构建幂等性 key：eventType + teamId [+ userId]。
     * 成员事件需要 teamId + userId 组合去重，团队事件仅需 teamId。
     */
    private String buildIdempotencyKey(String eventType, JsonNode root) {
        long teamId = root.path("teamId").asLong(0);
        long userId = root.path("userId").asLong(0);
        if (userId > 0) {
            return IDEMPOTENCY_KEY_PREFIX + eventType + ":" + teamId + ":" + userId;
        }
        return IDEMPOTENCY_KEY_PREFIX + eventType + ":" + teamId;
    }

    // ---- 事件 record → 内部 DTO 映射 ----

    private static InternalTeamSyncRequest toTeamSyncRequest(TeamCreatedEvent event) {
        InternalTeamSyncRequest request = new InternalTeamSyncRequest();
        request.setTeamId(event.teamId());
        request.setName(event.name());
        request.setAvatar(event.avatar());
        request.setDescription(event.description());
        request.setOwnerUserId(event.ownerUserId());
        request.setOwnerUsername(event.ownerUsername());
        request.setOwnerName(event.ownerName());
        request.setOwnerEmail(event.ownerEmail());
        return request;
    }

    private static InternalTeamSyncRequest toTeamSyncRequest(TeamUpdatedEvent event) {
        InternalTeamSyncRequest request = new InternalTeamSyncRequest();
        request.setTeamId(event.teamId());
        request.setName(event.name());
        request.setAvatar(event.avatar());
        request.setDescription(event.description());
        request.setOwnerUserId(event.ownerUserId());
        request.setOwnerUsername(event.ownerUsername());
        request.setOwnerName(event.ownerName());
        request.setOwnerEmail(event.ownerEmail());
        return request;
    }

    private static InternalTeamMemberSyncRequest toMemberSyncRequest(TeamMemberAddedEvent event) {
        InternalTeamMemberSyncRequest request = new InternalTeamMemberSyncRequest();
        request.setTeamId(event.teamId());
        request.setUserId(event.userId());
        request.setUsername(event.username());
        request.setName(event.name());
        request.setEmail(event.email());
        request.setAvatar(event.avatar());
        request.setRoleCode(event.roleCode());
        return request;
    }

    private static InternalTeamMemberRemovalRequest toMemberRemovalRequest(TeamMemberRemovedEvent event) {
        InternalTeamMemberRemovalRequest request = new InternalTeamMemberRemovalRequest();
        request.setTeamId(event.teamId());
        request.setUserId(event.userId());
        return request;
    }
}
