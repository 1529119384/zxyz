package uno.acloud.file.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.file.config.RabbitMqConfig;
import uno.acloud.file.service.impl.ProjectAccessCacheService;

/**
 * 监听项目成员变更事件，失效项目访问缓存。
 * <p>当 project-service 发布 project.member.added / project.member.removed 事件时，
 * 本消费者清除 file-service 中该项目的访问缓存，确保后续文件操作重新校验权限。</p>
 */
@Slf4j
@Component
public class ProjectMemberEventConsumer {

    private final ObjectMapper objectMapper;
    private final ProjectAccessCacheService projectAccessCacheService;

    public ProjectMemberEventConsumer(ObjectMapper objectMapper,
                                      ProjectAccessCacheService projectAccessCacheService) {
        this.objectMapper = objectMapper;
        this.projectAccessCacheService = projectAccessCacheService;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_PROJECT_MEMBER_EVENTS)
    public void handleMessage(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            Long projectId = root.path("projectId").asLong();
            if (projectId <= 0) {
                log.warn("项目成员事件缺少有效 projectId: {}", message);
                return;
            }
            projectAccessCacheService.evictProject(projectId);
            log.debug("项目成员变更事件已处理，访问缓存已失效: projectId={}", projectId);
        } catch (JsonProcessingException e) {
            log.error("项目成员事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("项目成员事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理项目成员变更事件失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理项目成员变更事件失败", e);
        }
    }
}
