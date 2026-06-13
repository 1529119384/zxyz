package uno.acloud.im.infrastructure.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uno.acloud.im.application.ImRealtimePushService;
import uno.acloud.im.infrastructure.netty.protocol.ImEnvelopeFactory;
import uno.acloud.im.vo.ConversationReadVO;
import uno.acloud.im.vo.ImMessageVO;
import uno.acloud.im.vo.MessageRecallVO;

import java.util.Collection;

@Slf4j
@Service
public class NettyImRealtimePushService implements ImRealtimePushService {

    private final ImConnectionRegistry connectionRegistry;
    private final ImEnvelopeFactory envelopeFactory;
    private final ObjectMapper objectMapper;

    public NettyImRealtimePushService(ImConnectionRegistry connectionRegistry,
                                      ImEnvelopeFactory envelopeFactory,
                                      ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.envelopeFactory = envelopeFactory;
        this.objectMapper = objectMapper;
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
        for (Channel channel : connectionRegistry.listChannels(userIds)) {
            try {
                channel.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(envelope)));
            } catch (Exception e) {
                log.warn("IM realtime push failed: channel={}", channel.id().asShortText(), e);
                channel.close();
            }
        }
    }
}
