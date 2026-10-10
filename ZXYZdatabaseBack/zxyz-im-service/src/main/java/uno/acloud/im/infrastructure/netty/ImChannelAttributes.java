package uno.acloud.im.infrastructure.netty;

import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicLong;

public final class ImChannelAttributes {

    public static final AttributeKey<Long> USER_ID = AttributeKey.valueOf("im.userId");
    public static final AttributeKey<String> AUTHORIZATION = AttributeKey.valueOf("im.authorization");
    /** 最近一条非 PING 消息的时间戳（毫秒），用于 per-channel 速率限制 */
    public static final AttributeKey<Long> LAST_MSG_TIMESTAMP = AttributeKey.valueOf("im.lastMsgTimestamp");
    /**
     * 握手鉴权成功后记录的<b>原始 token 值</b>（无 Bearer 前缀），供会话续签使用。
     * 与 AUTHORIZATION 的区别：AUTHORIZATION 携带 {@code "Bearer "} 前缀、面向下游
     * ImCommandRequest；本 attr 面向 Sa-Token API（需裸 token）。
     */
    public static final AttributeKey<String> TOKEN = AttributeKey.valueOf("im.saToken");
    /**
     * 会话续签节流时间戳（{@code ImSessionKeepAliveService.MIN_RENEW_INTERVAL_MS} 内跳过）。
     * 挂在 Channel 上与连接同生命周期，连接关闭随 Channel 一起释放，无泄漏风险。
     */
    public static final AttributeKey<AtomicLong> LAST_SA_TOKEN_RENEW_AT =
            AttributeKey.valueOf("im.lastSaTokenRenewAt");

    private ImChannelAttributes() {
    }
}
