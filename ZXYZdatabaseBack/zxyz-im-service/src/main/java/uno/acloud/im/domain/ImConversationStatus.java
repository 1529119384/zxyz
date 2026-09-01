package uno.acloud.im.domain;

/**
 * IM 会话状态常量。
 * 对应 {@code im_conversation.status}（Integer 0），以 int 常量定义，便于与字段直接比较。
 * 不再复用 ConversationMemberStatus，会话状态与成员状态语义不同。
 */
public final class ImConversationStatus {

    /** 活跃。 */
    public static final int ACTIVE = 0;

    private ImConversationStatus() {
    }
}
