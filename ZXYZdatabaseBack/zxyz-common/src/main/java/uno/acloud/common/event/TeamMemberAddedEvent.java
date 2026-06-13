package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 团队成员加入事件。
 * <p>team-service 发布，im-service 消费用于成员同步。</p>
 */
public record TeamMemberAddedEvent(
        String eventType,
        int version,
        long timestamp,
        long teamId,
        long userId,
        String username,
        String name,
        String email,
        String avatar,
        String roleCode,
        long sequenceNumber
) implements BaseEvent.EventBody {

    public TeamMemberAddedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_TEAM_MEMBER_ADDED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static TeamMemberAddedEvent of(long teamId, long userId, String username, String name,
                                           String email, String avatar, String roleCode, long sequenceNumber) {
        return new TeamMemberAddedEvent(null, 0, 0, teamId, userId, username, name, email, avatar, roleCode, sequenceNumber);
    }
}
