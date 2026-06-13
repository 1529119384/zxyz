package uno.acloud.file.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.mq.MqRetryTemplateFactory;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.FileResourceChangedEvent;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.service.FileQueryPort;

import java.util.List;

/**
 * 发布文件资源变更事件到 RabbitMQ。
 *
 * <p>使用 {@link FileResourceChangedEvent} 结构化事件 record 替代
 * 旧的 {@code FileResourceChangedEventVO}，携带 eventType/version/timestamp 元数据。</p>
 */
@Slf4j
@Component
public class FileResourceChangedPublisher {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final FileQueryPort fileQueryPort;
    private final RetryTemplate retryTemplate;

    public FileResourceChangedPublisher(RabbitTemplate rabbitTemplate,
                                        ObjectMapper objectMapper,
                                        FileQueryPort fileQueryPort) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.fileQueryPort = fileQueryPort;
        this.retryTemplate = MqRetryTemplateFactory.createDefault(getClass().getName());
    }

    public void publishByIds(String eventType, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        List<FileInfoDTO> currentFiles = fileQueryPort.getFileInfoByIds(fileIds);
        for (Long fileId : fileIds) {
            FileInfoDTO fileInfo = currentFiles.stream().filter(item -> fileId.equals(item.getId())).findFirst().orElse(null);
            FileResourceChangedEvent event = FileResourceChangedEvent.of(
                    eventType,
                    fileId,
                    fileInfo == null ? null : fileInfo.getParentId(),
                    fileInfo == null ? null : fileInfo.getStorePath(),
                    fileInfo == null ? 1 : fileInfo.getDeleted(),
                    fileInfo == null ? null : fileInfo.getModifyTime()
            );
            publish(eventType, event);
        }
    }

    public void publishFromSnapshots(String eventType, List<FileInfoDTO> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        for (FileInfoDTO snapshot : snapshots) {
            FileResourceChangedEvent event = FileResourceChangedEvent.of(
                    eventType,
                    snapshot.getId(),
                    snapshot.getParentId(),
                    snapshot.getStorePath(),
                    snapshot.getDeleted(),
                    snapshot.getModifyTime()
            );
            publish(eventType, event);
        }
    }

    private void publish(String eventType, FileResourceChangedEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "序列化文件资源变更事件失败: eventType=" + eventType + ", fileId=" + event.fileId());
        }
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, RabbitMqConstants.ROUTING_KEY_FILE_RESOURCE_CHANGED, json);
                log.debug("发布文件资源变更事件到 RabbitMQ: eventType={}, fileId={}", eventType, event.fileId());
                return null;
            });
        } catch (Exception e) {
            log.error("发布文件资源变更事件失败（已重试{}次）: eventType={}, fileId={}", MAX_RETRY_ATTEMPTS, eventType, event.fileId(), e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "MQ事件发布失败: " + eventType);
        }
    }
}
