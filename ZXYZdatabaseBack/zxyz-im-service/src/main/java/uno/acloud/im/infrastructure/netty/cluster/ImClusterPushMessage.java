package uno.acloud.im.infrastructure.netty.cluster;

import java.util.List;
import java.util.Objects;

/**
 * 跨节点推送消息体。
 *
 * <p>{@code originNodeId} 用于去重：发起推送的节点已经把消息投递给本节点持有的连接，
 * 因此收到自己发出的广播时直接丢弃，避免同一条消息被投递两次。</p>
 */
public record ImClusterPushMessage(String originNodeId, List<Long> targetUserIds, String envelopeJson) {

    public ImClusterPushMessage {
        targetUserIds = targetUserIds == null
                ? List.of()
                : targetUserIds.stream().filter(Objects::nonNull).toList();
    }
}
