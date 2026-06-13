package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 团队创建事件。
 * <p>team-service 发布，im-service 消费用于团队同步。</p>
 */
public record TeamCreatedEvent(
        String eventType,
        int version,
        long timestamp,
        long teamId,
        String name,
        String avatar,
        String description,
        long ownerUserId,
        String ownerUsername,
        String ownerName,
        String ownerEmail
) implements BaseEvent.EventBody {

    public TeamCreatedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_TEAM_CREATED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static TeamCreatedEvent of(long teamId, String name, String avatar, String description,
                                       long ownerUserId, String ownerUsername, String ownerName, String ownerEmail) {
        return new TeamCreatedEvent(null, 0, 0, teamId, name, avatar, description,
                ownerUserId, ownerUsername, ownerName, ownerEmail);
    }
}
