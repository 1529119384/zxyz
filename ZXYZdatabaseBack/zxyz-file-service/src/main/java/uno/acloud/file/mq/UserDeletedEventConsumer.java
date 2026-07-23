package uno.acloud.file.mq;

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
import uno.acloud.common.mq.MqRetryTemplateFactory;
import uno.acloud.file.config.RabbitMqConfig;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;
import uno.acloud.file.service.impl.FileObjectReferenceManager;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 消费用户注销/删除事件，清理用户个人空间文件。
 *
 * <p>file-service 监听 user.deleted 路由键，执行以下清理流程：
 * <ol>
 *   <li>查找用户个人空间的所有根文件节点</li>
 *   <li>递归收集所有后代节点 ID</li>
 *   <li>硬删除所有相关 file_node 记录</li>
 *   <li>通过 FileObjectReferenceManager 调度 OSS 对象物理删除</li>
 * </ol>
 * </p>
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:user:deleted:";
    private static final long IDEMPOTENCY_TTL_HOURS = 24;

    private final ObjectMapper objectMapper;
    private final FileMapper fileMapper;
    private final FileObjectReferenceManager fileObjectReferenceManager;
    private final StringRedisTemplate redisTemplate;

    public UserDeletedEventConsumer(ObjectMapper objectMapper,
                                    FileMapper fileMapper,
                                    FileObjectReferenceManager fileObjectReferenceManager,
                                    StringRedisTemplate redisTemplate) {
        this.objectMapper = objectMapper;
        this.fileMapper = fileMapper;
        this.fileObjectReferenceManager = fileObjectReferenceManager;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_USER_EVENTS)
    public void handleUserEvent(String message) {
        try {
            UserDeletedEvent event = objectMapper.readValue(message, UserDeletedEvent.class);
            String eventType = event.eventType();
            if (!RabbitMqConstants.ROUTING_KEY_USER_DELETED.equals(eventType)) {
                log.debug("MQ: 忽略非 user.deleted 用户事件: eventType={}", eventType);
                return;
            }

            long userId = event.userId();
            String username = event.username();

            // 幂等性检查：userId 作为去重 key
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + userId;
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复用户删除事件，跳过处理: key={}", idempotencyKey);
                return;
            }

            log.info("MQ: 开始清理用户个人空间文件: userId={}, username={}", userId, username);
            cleanupUserPersonalFiles(userId);
            log.info("MQ: 用户个人空间文件清理完成: userId={}, username={}", userId, username);
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }

    /**
     * 清理用户的个人空间文件：硬删除 file_node 记录 + 调度 OSS 对象删除。
     */
    @Transactional(rollbackFor = Exception.class)
    protected void cleanupUserPersonalFiles(long userId) {
        List<Long> rootFileIds = fileMapper.getPersonalRootFileIds(userId);
        if (rootFileIds == null || rootFileIds.isEmpty()) {
            log.debug("用户无个人空间文件: userId={}", userId);
            return;
        }

        // 递归收集所有后代节点 ID
        List<Long> allIds = fileMapper.collectDescendantIds(rootFileIds);
        if (allIds == null || allIds.isEmpty()) {
            log.warn("收集文件树节点失败: userId={}", userId);
            return;
        }

        // 获取 OSS 对象 key（用于调度物理删除）
        List<String> ossKeys = fileMapper.getOssKeysByIds(allIds);

        // 硬删除所有相关 file_node 记录
        int deleted = fileMapper.reallyDeleteByIds(allIds, userId);
        if (deleted != allIds.size()) {
            log.warn("部分文件节点删除失败: userId={}, expected={}, actual={}", userId, allIds.size(), deleted);
        }
        log.info("硬删除用户个人文件完成: userId={}, count={}", userId, deleted);

        // 调度 OSS 对象物理删除（引用计数归零后由 FileObjectPhysicalDeleteExecutor 异步清理）
        if (!ossKeys.isEmpty()) {
            fileObjectReferenceManager.releaseReferences(ossKeys);
        }
    }
}
