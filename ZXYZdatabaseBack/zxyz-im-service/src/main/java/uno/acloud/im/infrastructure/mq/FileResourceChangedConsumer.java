package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import uno.acloud.common.event.FileResourceChangedEvent;
import uno.acloud.im.application.FileCardResolveCache;
import uno.acloud.im.config.RabbitMqConfig;

/**
 * 消费文件资源变更事件。
 *
 * <p>反序列化为 {@link FileResourceChangedEvent} 结构化事件 record，
 * 替代旧的 im-service 内部 {@code FileResourceChangedEvent} 领域模型。</p>
 */
@Slf4j
@Component
public class FileResourceChangedConsumer {

    private final ObjectMapper objectMapper;
    private final FileCardResolveCache fileCardResolveCache;

    public FileResourceChangedConsumer(ObjectMapper objectMapper, FileCardResolveCache fileCardResolveCache) {
        this.objectMapper = objectMapper;
        this.fileCardResolveCache = fileCardResolveCache;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE)
    public void handleMessage(String message) {
        try {
            FileResourceChangedEvent event = objectMapper.readValue(message, FileResourceChangedEvent.class);
            if (event.fileId() != null) {
                fileCardResolveCache.invalidateByFileId(event.fileId());
            }
        } catch (JsonProcessingException e) {
            log.error("文件资源变更消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("文件资源变更消息反序列化失败", e);
        } catch (Exception e) {
            log.error("处理文件资源变更 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理文件资源变更消息失败", e);
        }
    }
}
