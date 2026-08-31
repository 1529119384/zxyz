package uno.acloud.im.infrastructure.netty.cluster;

import org.springframework.stereotype.Component;
import uno.acloud.im.config.ImClusterProperties;

import java.net.InetAddress;

/**
 * 当前 im-service 节点标识。
 *
 * <p>用于跨节点推送去重：发起推送的节点把自己的 {@code nodeId} 写入
 * {@link ImClusterPushMessage#originNodeId()}，订阅端收到后若发现是自身发出的广播则丢弃，
 * 避免同一条消息被投递两次。</p>
 *
 * <p>节点 ID 通过 {@link ImClusterProperties#getNodeId()} 配置；未配置时回退为
 * {@code hostname:pid}，保证集群内唯一即可。</p>
 */
@Component
public class ImClusterNode {

    private final String nodeId;

    public ImClusterNode(ImClusterProperties properties) {
        String configured = properties.getNodeId();
        if (configured != null && !configured.isBlank()) {
            this.nodeId = configured;
        } else {
            this.nodeId = defaultNodeId();
        }
    }

    public String nodeId() {
        return nodeId;
    }

    private static String defaultNodeId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown";
        }
        long pid = ProcessHandle.current().pid();
        return host + ":" + pid;
    }
}
