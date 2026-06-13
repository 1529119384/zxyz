package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 团队成员移除事件。
 * <p>team-service 发布，im-service 消费用于成员同步。</p>
 */
public record TeamMemberRemovedEvent(
        String eventType,
        int version,
        long timestamp,
        long teamId,
        long userId,
        long sequenceNumber
) implements BaseEvent.EventBody {

    public TeamMemberRemovedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_REMOVED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static TeamMemberRemovedEvent of(long teamId, long userId, long sequenceNumber) {
        return new TeamMemberRemovedEvent(null, 0, 0, teamId, userId, sequenceNumber);
    }
}
