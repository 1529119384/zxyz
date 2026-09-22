package uno.acloud.common.event;

import java.time.LocalDateTime;

/**
 * 文件资源变更事件。
 *
 * <p>file-service 发布；im-service（文件卡片缓存）与 project-service（存储用量缓存）消费。</p>
 *
 * <p>⚠️ 字段可空性（2026-09-21 补注）：{@code teamId} / {@code projectId} 是消费端做
 * <b>定向缓存失效</b>的依据 —— 拿不到 teamId 时消费端只能退化为「全量失效」。
 * 因此发布端必须尽力填充 teamId，仅在确实没有团队归属（如个人云盘文件）时才允许为 null。</p>
 *
 * <p>🔴 缺陷历史：此前存在一个 6 参的 {@code of(...)} 重载，把 teamId/projectId <b>硬编码为 null</b>，
 * 而 {@code FileResourceChangedPublisher.publishByIds} 恰恰走了那个重载 ⇒ 该路径下
 * <b>所有事件的 teamId 恒为 null</b> ⇒ 定向失效从未生效、每次都全量失效
 * （消费端又是空 catch 无日志，所以线上完全不可观测）。
 * <b>现已删除该重载</b>，使「忘记填 teamId」从运行期静默缺陷变成编译期错误。</p>
 */
public record FileResourceChangedEvent(
        String eventType,
        int version,
        long timestamp,
        Long fileId,
        Long parentId,
        String storePath,
        Integer deleted,
        LocalDateTime modifyTime,
        Long teamId,
        Long projectId
) implements BaseEvent.EventBody {

    public FileResourceChangedEvent {
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    /**
     * 唯一构造入口 —— 刻意<b>不</b>提供省略 teamId/projectId 的重载：
     * 在本事件里「省略」等价于「静默退化为全量缓存失效」，那正是要消灭的缺陷形态。
     */
    public static FileResourceChangedEvent of(String eventType, Long fileId, Long parentId,
                                               String storePath, Integer deleted, LocalDateTime modifyTime,
                                               Long teamId, Long projectId) {
        return new FileResourceChangedEvent(eventType, 0, 0, fileId, parentId, storePath, deleted, modifyTime, teamId, projectId);
    }
}
