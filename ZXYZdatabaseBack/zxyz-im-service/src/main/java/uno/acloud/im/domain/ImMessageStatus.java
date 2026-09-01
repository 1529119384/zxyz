package uno.acloud.im.domain;

/**
 * IM 消息状态常量。
 * 对应 {@code im_message.status}（Integer 0/1），以 int 常量定义，便于与字段直接比较。
 */
public final class ImMessageStatus {

    /** 已存储（未撤回）。 */
    public static final int STORED = 0;
    /** 已撤回。 */
    public static final int RECALLED = 1;

    private ImMessageStatus() {
    }
}
