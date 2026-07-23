package uno.acloud.common.event;

import uno.acloud.common.RabbitMqConstants;

/**
 * 用户注销/删除事件。
 * <p>user-service 发布，各服务消费用于跨服务数据清理：
 * file-service 清理用户个人空间，share-service 清理用户分享，
 * im-service 清理用户会话成员，team-service 移除用户团队成员关系，
 * project-service 移除用户项目成员关系。</p>
 */
public record UserDeletedEvent(
        String eventType,
        int version,
        long timestamp,
        long userId,
        String username
) implements BaseEvent.EventBody {

    public UserDeletedEvent {
        if (eventType == null) eventType = RabbitMqConstants.ROUTING_KEY_USER_DELETED;
        if (version == 0) version = 1;
        if (timestamp == 0) timestamp = System.currentTimeMillis();
    }

    public static UserDeletedEvent of(long userId, String username) {
        return new UserDeletedEvent(null, 0, 0, userId, username);
    }
}
