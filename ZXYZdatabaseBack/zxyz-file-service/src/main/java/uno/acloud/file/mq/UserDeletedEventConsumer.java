package uno.acloud.file.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;
import uno.acloud.file.config.RabbitMqConfig;
import uno.acloud.file.service.FileUserCleanupService;

/**
 * 消费用户注销/删除事件，委托给 FileUserCleanupService 执行清理。
 */
@Slf4j
@Component
public class UserDeletedEventConsumer {

    private final ObjectMapper objectMapper;
    private final FileUserCleanupService cleanupService;

    public UserDeletedEventConsumer(ObjectMapper objectMapper, FileUserCleanupService cleanupService) {
        this.objectMapper = objectMapper;
        this.cleanupService = cleanupService;
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

            if (!cleanupService.tryAcquireIdempotencyKey(userId)) {
                // F4：区分「已真正完成」与「另一个消费者正在处理」，不要把两者都误报成「重复事件」——
                // 后者在上一消费者崩溃时只是短 TTL 未过期的暂时状态，误报会掩盖真实故障。
                if (cleanupService.isCleanupCompleted(userId)) {
                    log.info("MQ: 用户删除事件已处理完成，跳过: userId={}", userId);
                } else {
                    log.warn("MQ: 用户删除事件正在处理中（并发或上次崩溃的短 TTL 未过期），本次跳过: userId={}", userId);
                }
                return;
            }

            try {
                log.info("MQ: 开始清理用户个人空间文件: userId={}, username={}", userId, username);
                cleanupService.cleanupUserPersonalFiles(userId);
                // 关键（F4）：清理**成功**后才写长 TTL 的 done 键；此前只有一个 24h 的「认领」键，
                // 进程若在清理完成前崩溃，键会残留 24 小时压制全部重投递，「半途而废」被误报成「重复事件」。
                cleanupService.markCleanupCompleted(userId);
                log.info("MQ: 用户个人空间文件清理完成: userId={}, username={}", userId, username);
            } catch (Exception e) {
                cleanupService.releaseIdempotencyKey(userId);
                log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, userId={}, message={}", userId, message, e);
                throw new RuntimeException("处理用户删除事件消息失败", e);
            }
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }
}
