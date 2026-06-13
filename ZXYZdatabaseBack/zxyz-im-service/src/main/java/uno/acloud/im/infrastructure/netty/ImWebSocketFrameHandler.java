package uno.acloud.im.infrastructure.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uno.acloud.im.application.ImCommandDispatcher;
import uno.acloud.im.application.ImCommandRequest;
import uno.acloud.im.application.ImCommandResult;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.infrastructure.netty.protocol.ImEnvelope;
import uno.acloud.im.infrastructure.netty.protocol.ImEnvelopeFactory;

@Slf4j
@Component
@ChannelHandler.Sharable
public class ImWebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    /** 每条消息的最小间隔（毫秒），per-channel 限流；50ms = 每秒最多 20 条 */
    private static final long MIN_MESSAGE_INTERVAL_MS = 50;

    private final ObjectMapper objectMapper;
    private final ImEnvelopeFactory envelopeFactory;
    private final ImConnectionRegistry connectionRegistry;
    private final ImCommandDispatcher commandDispatcher;

    public ImWebSocketFrameHandler(ObjectMapper objectMapper,
                                   ImEnvelopeFactory envelopeFactory,
                                   ImConnectionRegistry connectionRegistry,
                                   ImCommandDispatcher commandDispatcher) {
        this.objectMapper = objectMapper;
        this.envelopeFactory = envelopeFactory;
        this.connectionRegistry = connectionRegistry;
        this.commandDispatcher = commandDispatcher;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Long userId = ctx.channel().attr(ImChannelAttributes.USER_ID).get();
            if (userId == null) {
                ctx.close();
                return;
            }
            int connectionCount = connectionRegistry.register(userId, ctx.channel());
            writeEnvelope(ctx, envelopeFactory.authOk(userId, connectionCount));
            log.info("IM WebSocket authenticated: userId={}, channel={}, connectionCount={}",
                    userId, ctx.channel().id().asShortText(), connectionCount);
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        ImEnvelope request = null;
        try {
            request = objectMapper.readValue(frame.text(), ImEnvelope.class);
            if ("PING".equals(request.getType())) {
                writeEnvelope(ctx, envelopeFactory.pong(request));
                return;
            }
            // per-channel 速率限制：非 PING 消息之间必须间隔至少 MIN_MESSAGE_INTERVAL_MS
            long now = System.currentTimeMillis();
            Long lastMsgTime = ctx.channel().attr(ImChannelAttributes.LAST_MSG_TIMESTAMP).get();
            if (lastMsgTime != null && now - lastMsgTime < MIN_MESSAGE_INTERVAL_MS) {
                log.debug("Rate limit hit for channel {}", ctx.channel().id().asShortText());
                writeEnvelope(ctx, envelopeFactory.error(request.getRequestId(), "消息发送过于频繁，请稍后再试"));
                return;
            }
            ctx.channel().attr(ImChannelAttributes.LAST_MSG_TIMESTAMP).set(now);

            ImCommandResult result = commandDispatcher.dispatch(toCommandRequest(ctx, request));
            writeEnvelope(ctx, envelopeFactory.messageAck(
                    result.requestId(),
                    result.clientMessageId(),
                    result.conversationId(),
                    result.messageId()
            ));
        } catch (BusinessException e) {
            String requestId = request == null ? null : request.getRequestId();
            log.warn("IM command failed: type={}, requestId={}, error={}",
                    request == null ? null : request.getType(), requestId, e.getMessage(), e);
            writeEnvelope(ctx, envelopeFactory.error(requestId, "消息处理失败"));
        } catch (Exception e) {
            String requestId = request == null ? null : request.getRequestId();
            writeEnvelope(ctx, envelopeFactory.error(requestId, "消息协议解析失败"));
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Long userId = ctx.channel().attr(ImChannelAttributes.USER_ID).get();
        if (userId != null) {
            int connectionCount = connectionRegistry.unregister(userId, ctx.channel());
            log.info("IM WebSocket disconnected: userId={}, channel={}, connectionCount={}",
                    userId, ctx.channel().id().asShortText(), connectionCount);
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("IM WebSocket channel error: {}", ctx.channel().id().asShortText(), cause);
        ctx.close();
    }

    private ImCommandRequest toCommandRequest(ChannelHandlerContext ctx, ImEnvelope request) {
        return new ImCommandRequest(
                ctx.channel().attr(ImChannelAttributes.USER_ID).get(),
                ctx.channel().attr(ImChannelAttributes.AUTHORIZATION).get(),
                request.getType(),
                request.getRequestId(),
                request.getClientMessageId(),
                request.getConversationId(),
                request.getPayload()
        );
    }

    private void writeEnvelope(ChannelHandlerContext ctx, Object envelope) {
        try {
            ctx.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(envelope)));
        } catch (Exception e) {
            log.warn("IM WebSocket write failed: {}", ctx.channel().id().asShortText(), e);
            ctx.close();
        }
    }
}
