package uno.acloud.im.infrastructure.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.config.ImClusterProperties;
import uno.acloud.im.infrastructure.netty.cluster.ImClusterKeys;
import uno.acloud.im.infrastructure.netty.cluster.ImClusterNode;
import uno.acloud.im.infrastructure.netty.cluster.ImClusterPushMessage;
import uno.acloud.im.infrastructure.netty.protocol.ImEnvelopeFactory;
import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

import java.util.ArrayList;
import java.util.Collection;

@Slf4j
@Service
public class NettyImRealtimePushService implements ImRealtimePushService {

    private final ImConnectionRegistry connectionRegistry;
    private final ImEnvelopeFactory envelopeFactory;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ImClusterProperties clusterProperties;
    private final ImClusterNode clusterNode;

    public NettyImRealtimePushService(ImConnectionRegistry connectionRegistry,
                                      ImEnvelopeFactory envelopeFactory,
                                      ObjectMapper objectMapper,
                                      StringRedisTemplate stringRedisTemplate,
                                      ImClusterProperties clusterProperties,
                                      ImClusterNode clusterNode) {
        this.connectionRegistry = connectionRegistry;
        this.envelopeFactory = envelopeFactory;
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.clusterProperties = clusterProperties;
        this.clusterNode = clusterNode;
    }

    @Override
    public void pushMessageReceived(Collection<Long> userIds, ImMessageVO message) {
        Object envelope = envelopeFactory.messageReceived(message);
        broadcast(userIds, envelope);
    }

    @Override
    public void pushReadUpdated(Collection<Long> userIds, ConversationReadVO readState) {
        Object envelope = envelopeFactory.readUpdated(readState);
        broadcast(userIds, envelope);
    }

    @Override
    public void pushMessageRecalled(Collection<Long> userIds, MessageRecallVO recall) {
        Object envelope = envelopeFactory.messageRecalled(recall);
        broadcast(userIds, envelope);
    }

    private void broadcast(Collection<Long> userIds, Object envelope) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        String envelopeJson;
        try {
            envelopeJson = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.warn("IM serialize envelope failed", e);
            return;
        }
        // 1) 先推给本节点持有的连接
        pushLocal(userIds, envelopeJson);
        // 2) 集群模式下再发布到 Redis 频道，由其他节点订阅后推给它们本地连接
        if (clusterProperties.isEnabled()) {
            try {
                ImClusterPushMessage clusterMsg = new ImClusterPushMessage(
                        clusterNode.nodeId(), new ArrayList<>(userIds), envelopeJson);
                stringRedisTemplate.convertAndSend(
                        ImClusterKeys.PUSH_CHANNEL, objectMapper.writeValueAsString(clusterMsg));
            } catch (Exception e) {
                log.warn("IM cluster publish failed: channel={}", ImClusterKeys.PUSH_CHANNEL, e);
            }
        }
    }

    /**
     * 仅向本节点 {@link ImConnectionRegistry} 中持有的连接推送（不发布到 Redis）。
     * 集群订阅端复用此方法投递「其他节点转发过来」的消息。
     */
    public void pushLocal(Collection<Long> userIds, String envelopeJson) {
        if (envelopeJson == null || envelopeJson.isEmpty()) {
            return;
        }
        for (Channel channel : connectionRegistry.listChannels(userIds)) {
            try {
                channel.writeAndFlush(new TextWebSocketFrame(envelopeJson));
            } catch (Exception e) {
                log.warn("IM realtime push failed: channel={}", channel.id().asShortText(), e);
                channel.close();
            }
        }
    }
}
