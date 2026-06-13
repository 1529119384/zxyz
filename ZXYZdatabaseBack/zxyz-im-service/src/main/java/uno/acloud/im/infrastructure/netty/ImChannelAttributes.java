package uno.acloud.im.infrastructure.netty;

import io.netty.util.AttributeKey;

public final class ImChannelAttributes {

    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("im.userId");
    public static final AttributeKey<String> AUTHORIZATION = AttributeKey.valueOf("im.authorization");
    /** 最近一条非 PING 消息的时间戳（毫秒），用于 per-channel 速率限制 */
    public static final AttributeKey<Long> LAST_MSG_TIMESTAMP = AttributeKey.valueOf("im.lastMsgTimestamp");

    private ImChannelAttributes() {
    }
}
