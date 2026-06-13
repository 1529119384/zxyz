package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 团队资料更新事件。
 * <p>team-service 发布，im-service 消费用于团队资料同步。</p>
 */
public record TeamUpdatedEvent(
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

    public TeamUpdatedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_TEAM_UPDATED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static TeamUpdatedEvent of(long teamId, String name, String avatar, String description,
                                       long ownerUserId, String ownerUsername, String ownerName, String ownerEmail) {
        return new TeamUpdatedEvent(null, 0, 0, teamId, name, avatar, description,
                ownerUserId, ownerUsername, ownerName, ownerEmail);
    }
}
