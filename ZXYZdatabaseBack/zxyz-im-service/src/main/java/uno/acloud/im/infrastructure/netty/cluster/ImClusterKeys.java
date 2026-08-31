package uno.acloud.im.infrastructure.netty.cluster;

/**
 * im-service 集群模式使用的 Redis key / 频道命名。
 */
public final class ImClusterKeys {

    /**
     * 用户路由表：{@code zxyz:im:route:user:{userId}} → Hash，field 为节点 ID，value 为该节点上该用户的连接数。
     */
    public static final String ROUTE_KEY_PREFIX = "zxyz:im:route:user:";

    /**
     * 节点心跳：{@code zxyz:im:node:{nodeId}} → 最近一次续期的时间戳（毫秒）。
     */
    public static final String NODE_KEY_PREFIX = "zxyz:im:node:";

    /**
     * 跨节点推送频道：{@code zxyz:im:cluster:push}。
     */
    public static final String PUSH_CHANNEL = "zxyz:im:cluster:push";

    private ImClusterKeys() {
    }

    public static String routeKey(Long userId) {
        return ROUTE_KEY_PREFIX + userId;
    }

    public static String nodeKey(String nodeId) {
        return NODE_KEY_PREFIX + nodeId;
    }
}
