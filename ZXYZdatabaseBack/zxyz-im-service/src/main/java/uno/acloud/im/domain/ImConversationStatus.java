package uno.acloud.im.domain;

/**
 * IM 会话状态常量。
 * 对应 {@code im_conversation.status}（Integer 0/1），以 int 常量定义，便于与字段直接比较。
 * 会话状态独立于成员状态，两者语义不同，取值见 {@code im_conversation.status} 字段定义。
 */
public final class ImConversationStatus {

    /** 活跃。 */
    public static final int ACTIVE = 0;

    /** 已解散。 */
    public static final int DISSOLVED = 1;

    private ImConversationStatus() {
    }
}
