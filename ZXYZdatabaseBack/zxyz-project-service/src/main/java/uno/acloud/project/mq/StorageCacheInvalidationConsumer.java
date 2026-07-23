package uno.acloud.project.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.project.config.RabbitMqConfig;
import uno.acloud.project.service.impl.StorageQuotaCacheService;

/**
 * 监听文件资源变更事件和团队成员变更事件，失效配额相关缓存。
 * <p>file.resource.changed → 清除存储用量缓存。
 * team.member.added / team.member.removed → 清除用户团队列表缓存。</p>
 */
@Slf4j
@Component
public class StorageCacheInvalidationConsumer {

    private final ObjectMapper objectMapper;
    private final StorageQuotaCacheService cacheService;

    public StorageCacheInvalidationConsumer(ObjectMapper objectMapper,
                                            StorageQuotaCacheService cacheService) {
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_FILE_EVENTS)
    public void handleFileEvent(String message) {
        try {
            // 解析 teamId 做定向缓存失效，避免全量清除导致雪崩
            Long teamId = null;
            try {
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(message);
                teamId = root.path("teamId").asLong(0);
                if (teamId <= 0) teamId = null;
            } catch (Exception ignored) {
            }
            if (teamId != null) {
                cacheService.invalidateTeamCache(teamId);
                log.debug("文件资源变更事件已处理，团队缓存已失效: teamId={}", teamId);
            } else {
                cacheService.invalidateAllUsageCaches();
                log.debug("文件资源变更事件已处理，存储用量缓存已全量失效");
            }
        } catch (Exception e) {
            log.error("处理文件资源变更事件失败（将重试）", e);
            throw new RuntimeException("处理文件资源变更事件失败", e);
        }
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_TEAM_MEMBER_EVENTS)
    public void handleTeamMemberEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            Long userId = root.path("userId").asLong(0);
            if (userId > 0) {
                cacheService.invalidateUserTeamIdsCache(userId);
                log.debug("团队成员变更事件已处理，用户团队列表缓存已失效: userId={}", userId);
            }
        } catch (JsonProcessingException e) {
            log.error("团队成员变更事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("团队成员变更事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理团队成员变更事件失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理团队成员变更事件失败", e);
        }
    }
}
