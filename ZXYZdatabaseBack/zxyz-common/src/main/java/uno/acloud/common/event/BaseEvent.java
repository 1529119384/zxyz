package uno.acloud.common.event;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * RabbitMQ 事件基类。
 *
 * <p>所有结构化事件携带公共元数据：
 * eventType（路由键）、version（事件格式版本）、timestamp（产生时间的 epoch 毫秒）。</p>
 *
 * <p>由于 Java record 不能继承抽象类，事件记录通过实现 {@link EventBody} 接口来保证
 * 携带相同的公共字段。传统类可直接继承 {@code BaseEvent}。</p>
 *
 * @see EventBody
 */
public abstract class BaseEvent {

    /** 事件类型，值等于 RabbitMQ 路由键（如 "team.created"） */
    private final String eventType;

    /** 事件格式版本，用于消费端兼容性判断 */
    private final int version;

    /** 事件产生时间的 epoch 毫秒 */
    private final long timestamp;

    protected BaseEvent(String eventType, int version) {
        this.eventType = eventType;
        this.version = version;
        this.timestamp = System.currentTimeMillis();
    }

    public String getEventType() { return eventType; }
    public int getVersion() { return version; }
    public long getTimestamp() { return timestamp; }

    /**
     * 事件体契约接口，供 Java record 实现。
     *
     * <p>所有结构化事件 record 必须实现此接口，并声明
     * {@code eventType}、{@code version}、{@code timestamp} 字段。
     * 可通过 compact constructor 设置默认值：</p>
     * <pre>{@code
     * public MyEvent {
     *     if (eventType == null) eventType = "my.event";
     *     if (version == 0) version = 1;
     *     if (timestamp == 0) timestamp = System.currentTimeMillis();
     * }
     * }</pre>
     */
    @JsonPropertyOrder({"eventType", "version", "timestamp"})
    public interface EventBody {
        String eventType();
        int version();
        long timestamp();
    }
}
