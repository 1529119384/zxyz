package uno.acloud.team.mq;

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
import uno.acloud.team.config.RabbitMqConfig;
import uno.acloud.team.mapper.TeamMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消费用户注销/删除事件，移除用户团队成员关系。
 *
 * <p>team-service 监听 user.deleted 路由键，将用户从所有团队中移除（status = 2）。</p>
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final TeamMapper teamMapper;
    private final StringRedisTemplate redisTemplate;

    public UserDeletedEventConsumer(ObjectMapper objectMapper,
                                    TeamMapper teamMapper,
                                    StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.teamMapper = teamMapper;
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

            log.info("MQ: 开始移除用户团队成员关系: userId={}, username={}", userId, username);
            removeUserFromTeams(userId);
            log.info("MQ: 用户团队成员关系移除完成: userId={}, username={}", userId, username);
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }

    /**
     * 将用户从所有团队中移除。
     * <p>注意：如果用户是团队的唯一大管理员，无法直接移除，需管理员先转移所有权。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    protected void removeUserFromTeams(long userId) {
        List<Long> teamIds = teamMapper.listMyTeams(userId).stream()
                .map(uno.acloud.team.entity.Team::getId)
                .toList();

        int removed = 0;
        int skipped = 0;
        for (Long teamId : teamIds) {
            try {
                int result = teamMapper.removeMember(teamId, userId);
                if (result > 0) {
                    removed++;
                }
            } catch (Exception e) {
                log.warn("移除用户团队成员关系失败（可能用户是团队所有者）: userId={}, teamId={}", userId, teamId, e);
                skipped++;
            }
        }
        log.info("移除用户团队成员关系完成: userId={}, removed={}, skipped={}", userId, removed, skipped);
    }
}
