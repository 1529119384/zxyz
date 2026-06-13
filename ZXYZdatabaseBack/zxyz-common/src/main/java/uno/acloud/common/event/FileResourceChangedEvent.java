package uno.acloud.common.event;

import java.time.LocalDateTime;

/**
 * 文件资源变更事件。
 * <p>file-service 发布，im-service 消费用于文件卡片缓存失效。</p>
 */
public record FileResourceChangedEvent(
        String eventType,
        int version,
        long timestamp,
        Long fileId,
        Long parentId,
        String storePath,
        Integer deleted,
        LocalDateTime modifyTime
) implements BaseEvent.EventBody {

    public FileResourceChangedEvent {
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static FileResourceChangedEvent of(String eventType, Long fileId, Long parentId,
                                               String storePath, Integer deleted, LocalDateTime modifyTime) {
        return new FileResourceChangedEvent(eventType, 0, 0, fileId, parentId, storePath, deleted, modifyTime);
    }
}
