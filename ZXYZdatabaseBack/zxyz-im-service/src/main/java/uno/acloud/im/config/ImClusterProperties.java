package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * im-service 集群（水平扩容）配置。
 *
 * <p>默认关闭：关闭时 im-service 只维护本地连接，行为与改造前的单节点部署完全一致。
 * 开启后连接路由表写入 Redis，跨节点推送通过 Redis Pub/Sub 广播。</p>
 */
@ConfigurationProperties(prefix = "app.im.cluster")
public class ImClusterProperties {

    /** 是否启用集群模式。 */
    private boolean enabled = false;

    /** 本节点 ID，集群内必须唯一；未配置时自动生成 {@code hostname:pid}。 */
    private String nodeId;

    /** 用户路由表 key 的过期时间（秒），节点异常退出后脏数据最多残留该时长。 */
    private long routeTtlSeconds = 120;

    /** 节点心跳 key 的过期时间（秒），需小于 routeTtlSeconds，否则失效节点无法在路由表过期前被识别。 */
    private long heartbeatTtlSeconds = 30;

    /** 路由表与心跳的续期周期（秒）。 */
    private long renewIntervalSeconds = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public long getRouteTtlSeconds() {
        return routeTtlSeconds;
    }

    public void setRouteTtlSeconds(long routeTtlSeconds) {
        this.routeTtlSeconds = routeTtlSeconds;
    }

    public long getHeartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }

    public void setHeartbeatTtlSeconds(long heartbeatTtlSeconds) {
        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
    }

    public long getRenewIntervalSeconds() {
        return renewIntervalSeconds;
    }

    public void setRenewIntervalSeconds(long renewIntervalSeconds) {
        this.renewIntervalSeconds = renewIntervalSeconds;
    }
}
