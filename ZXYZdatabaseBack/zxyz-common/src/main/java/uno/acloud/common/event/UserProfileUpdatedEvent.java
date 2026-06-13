package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 用户资料更新事件。
 * <p>user-service 发布，im-service 消费用于用户资料缓存同步。</p>
 */
public record UserProfileUpdatedEvent(
        String eventType,
        int version,
        long timestamp,
        long userId,
        String username,
        String name,
        String email,
        String avatar
) implements BaseEvent.EventBody {

    public UserProfileUpdatedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_USER_PROFILE_UPDATED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static UserProfileUpdatedEvent of(long userId, String username, String name, String email, String avatar) {
        return new UserProfileUpdatedEvent(null, 0, 0, userId, username, name, email, avatar);
    }
}
