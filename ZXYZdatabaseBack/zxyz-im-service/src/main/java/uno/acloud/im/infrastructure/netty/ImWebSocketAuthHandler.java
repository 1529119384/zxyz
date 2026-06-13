package uno.acloud.im.infrastructure.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.acloud.im.application.WsTicketService;
import uno.acloud.im.config.ImTokenAuthService;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@ChannelHandler.Sharable
public class ImWebSocketAuthHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final String BEARER_PROTOCOL = "Bearer";

    private final ImTokenAuthService tokenAuthService;
    private final WsTicketService ticketService;
    private final boolean allowTokenFallback;

    public ImWebSocketAuthHandler(ImTokenAuthService tokenAuthService, WsTicketService ticketService,
                                  @Value("${spring.profiles.active:prod}") String activeProfile) {
        this.tokenAuthService = tokenAuthService;
        this.ticketService = ticketService;
        this.allowTokenFallback = "dev".equals(activeProfile);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String token = resolveToken(request);

            // 优先：一次性 Ticket 模式
            Optional<WsTicketService.TicketInfo> ticketInfo = ticketService.resolveAndConsumeTicket(token);
            if (ticketInfo.isPresent()) {
                WsTicketService.TicketInfo info = ticketInfo.get();
                ctx.channel().attr(ImChannelAttributes.USER_ID).set(info.userId());
                ctx.channel().attr(ImChannelAttributes.AUTHORIZATION).set(BEARER_PROTOCOL + " " + info.saToken());
                ctx.fireChannelRead(request.retain());
                return;
            }

            // 降级：Sa-Token 模式（仅 dev 环境允许）
            if (!allowTokenFallback) {
                log.warn("IM WebSocket token fallback rejected: only allowed in dev environment");
                reject(ctx);
                return;
            }
            Long userId = tokenAuthService.resolveUserIdByToken(token);
            ctx.channel().attr(ImChannelAttributes.USER_ID).set(userId);
            ctx.channel().attr(ImChannelAttributes.AUTHORIZATION).set(BEARER_PROTOCOL + " " + token);
            ctx.fireChannelRead(request.retain());
        } catch (Exception e) {
            log.warn("IM WebSocket handshake authentication failed: {}", e.getMessage());
            reject(ctx);
        }
    }

    private String resolveToken(FullHttpRequest request) {
        // 从 Sec-WebSocket-Protocol 子协议读取 Token
        String protocolHeader = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (protocolHeader != null && !protocolHeader.isBlank()) {
            List<String> protocols = Arrays.stream(protocolHeader.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
            int bearerIndex = protocols.indexOf(BEARER_PROTOCOL);
            if (bearerIndex >= 0 && bearerIndex + 1 < protocols.size()) {
                return protocols.get(bearerIndex + 1);
            }
        }

        throw new IllegalArgumentException("missing authentication token");
    }

    private void reject(ChannelHandlerContext ctx) {
        byte[] body = "Unauthorized".getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                ctx.alloc().buffer(body.length).writeBytes(body)
        );
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ctx.writeAndFlush(response).addListener(future -> ctx.close());
    }
}
