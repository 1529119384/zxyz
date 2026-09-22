package uno.acloud.project.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.event.FileResourceChangedEvent;
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
        FileResourceChangedEvent event;
        try {
            // 类型化反序列化，替代此前手写的 `root.path("teamId").asLong(0)`。
            // 与 im-service 的 FileResourceChangedConsumer 采用**同一范式**：同一份载荷不应在
            // 两个消费端各自解析一遍（手写取值不会随事件字段变更而报错，只会静默取到 0）。
            event = objectMapper.readValue(message, FileResourceChangedEvent.class);
        } catch (JsonProcessingException e) {
            // 🔴 此处原先是一个空的 `catch (Exception ignored) {}`：解析失败被静默吞掉，
            //   随后静默退化为「全量失效」且不留任何日志 ⇒ 生产上无法区分
            //   「本就没有 teamId 的个人文件」与「载荷结构漂移导致 teamId 永远读不到」。
            //   现在改为响亮失败，并丢弃这条毒消息（不重投，避免无限重试风暴）——
            //   与同文件 handleTeamMemberEvent 的既有范式一致。
            log.error("文件资源变更事件反序列化失败（丢弃消息，不重投）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("文件资源变更事件反序列化失败", e);
        }
        try {
            Long teamId = event.teamId();
            if (teamId != null && teamId > 0) {
                cacheService.invalidateTeamCache(teamId);
                log.debug("文件资源变更事件已处理，团队缓存已**定向**失效: teamId={}, fileId={}", teamId, event.fileId());
            } else {
                // teamId 缺失的**合法**情形：个人云盘文件（无团队归属），或文件已被彻底删除
                // 导致 fileQueryPort 查不到归属。这两种情况只能全量失效，故保留 debug 级留痕，
                // 使其在需要排查「为什么又全量失效了」时可见。
                cacheService.invalidateAllUsageCaches();
                log.debug("文件资源变更事件已处理，teamId 缺失 ⇒ 存储用量缓存**全量**失效: eventType={}, fileId={}",
                        event.eventType(), event.fileId());
            }
        } catch (Exception e) {
            log.error("处理文件资源变更事件失败（将重试）, message={}", message, e);
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
