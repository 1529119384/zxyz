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

            // 幂等性检查：使用 eventType + 实体标识 + 序列号作为去重 key，防止重复消费
            String idempotencyKey = buildIdempotencyKey(eventType, root);
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复团队事件消息，跳过处理: key={}", idempotencyKey);
                return;
            }

            long sequenceNumber = root.path("sequenceNumber").asLong(0);
            long teamId = root.path("teamId").asLong(0);
            // 乱序检测只做「只读校验」，序列号在处理成功后才推进（见方法末尾）。
            // 若在此处就推进，处理失败后的重投会被自己写下的序列号判成「乱序」而永久丢弃，
            // 与幂等占位键「失败不释放」叠加，就会造成 team-service 与 im-service 的永久数据分歧。
            String sequenceKey = null;
            if (sequenceNumber > 0 && teamId > 0) {
                sequenceKey = "mq:sequence:team:" + teamId;
                String lastSequence = redisTemplate.opsForValue().get(sequenceKey);
                if (lastSequence != null && sequenceNumber <= Long.parseLong(lastSequence)) {
                    log.warn("MQ: 团队事件乱序丢弃: teamId={}, receivedSeq={}, lastSeq={}", teamId, sequenceNumber, lastSequence);
                    return;
                }
            }

            try {
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
            } catch (Exception e) {
                // 处理失败必须释放幂等占位键，否则 MQ 重投会被判成「重复消息」而静默丢弃，
                // 数据分歧被永久固化。范本：file-service 的 UserDeletedEventConsumer。
                releaseIdempotencyKey(idempotencyKey);
                throw e;
            }

            if (sequenceKey != null) {
                redisTemplate.opsForValue().set(sequenceKey, String.valueOf(sequenceNumber), 24, TimeUnit.HOURS);
                log.debug("MQ: 团队事件序列号已推进: eventType={}, seq={}", eventType, sequenceNumber);
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
     * 释放幂等占位键，使失败消息在 MQ 重投时能被真正重新处理。
     * Redis 自身异常只记日志，不得掩盖原始业务异常。
     */
    private void releaseIdempotencyKey(String idempotencyKey) {
        try {
            redisTemplate.delete(idempotencyKey);
            log.warn("MQ: 团队事件处理失败，已释放幂等占位键以便重投重试: key={}", idempotencyKey);
        } catch (Exception e) {
            log.error("MQ: 释放幂等占位键失败（该消息重投将被判为重复而跳过）: key={}", idempotencyKey, e);
        }
    }

    /**
     * 构建幂等性 key：eventType + teamId [+ userId] [+ seq{sequenceNumber}]。
     * <p>成员事件需要 teamId + userId 组合去重，团队事件仅需 teamId。</p>
     * <p>序列号是「事件自身的身份」（发布端单调递增，见 TeamEventPublisher），
     * 带上它才能区分同一实体在 TTL 窗口内的多次变更（如 移除 → 重新加入 → 再移除），
     * 否则第二条同类事件会被误判为重复投递而静默丢弃。</p>
     */
    private String buildIdempotencyKey(String eventType, JsonNode root) {
        long teamId = root.path("teamId").asLong(0);
        long userId = root.path("userId").asLong(0);
        long sequenceNumber = root.path("sequenceNumber").asLong(0);
        StringBuilder key = new StringBuilder(IDEMPOTENCY_KEY_PREFIX)
                .append(eventType).append(':').append(teamId);
        if (userId > 0) {
            key.append(':').append(userId);
        }
        if (sequenceNumber > 0) {
            key.append(":seq").append(sequenceNumber);
        }
        return key.toString();
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
