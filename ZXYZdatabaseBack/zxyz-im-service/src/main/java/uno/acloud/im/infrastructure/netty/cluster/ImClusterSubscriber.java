package uno.acloud.im.infrastructure.netty.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import uno.acloud.im.infrastructure.netty.NettyImRealtimePushService;

/**
 * IM 集群跨节点推送订阅端。
 *
 * <p>仅在 {@code app.im.cluster.enabled=true} 时装配：监听 {@link ImClusterKeys#PUSH_CHANNEL}，
 * 反序列化 {@link ImClusterPushMessage} 后只把消息投递给「本节点本地连接」
 * （通过 {@link NettyImRealtimePushService#pushLocal} 复用本地推送逻辑）。</p>
 *
 * <p>去重：发布端会把自身 {@code nodeId} 写入 {@code originNodeId}；
 * 本节点收到自己发出的广播时直接丢弃，保证每条消息仅被实际持有连接的那个节点推送一次。</p>
 *
 * <p>参考 {@code uno.acloud.common.config.ConfigClientAutoConfiguration} 的
 * {@link RedisMessageListenerContainer} + {@link ChannelTopic} 写法。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.im.cluster.enabled", havingValue = "true")
public class ImClusterSubscriber implements MessageListener {

    private final NettyImRealtimePushService pushService;
    private final ObjectMapper objectMapper;
    private final ImClusterNode clusterNode;

    public ImClusterSubscriber(NettyImRealtimePushService pushService,
                               ObjectMapper objectMapper,
                               ImClusterNode clusterNode) {
        this.pushService = pushService;
        this.objectMapper = objectMapper;
        this.clusterNode = clusterNode;
    }

    @Bean
    public RedisMessageListenerContainer imClusterPushContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(this, new ChannelTopic(ImClusterKeys.PUSH_CHANNEL));
        return container;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            ImClusterPushMessage clusterMsg = objectMapper.readValue(message.getBody(), ImClusterPushMessage.class);
            if (clusterMsg.originNodeId() != null && clusterMsg.originNodeId().equals(clusterNode.nodeId())) {
                // 发起节点已本地推送过，避免重复投递
                return;
            }
            pushService.pushLocal(clusterMsg.targetUserIds(), clusterMsg.envelopeJson());
        } catch (Exception e) {
            log.warn("IM cluster push message handle failed", e);
        }
    }
}
