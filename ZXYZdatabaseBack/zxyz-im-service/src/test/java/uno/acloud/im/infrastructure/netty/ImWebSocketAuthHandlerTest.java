package uno.acloud.im.infrastructure.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.im.application.WsTicketService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link ImWebSocketAuthHandler} 的<b>握手后 Channel attribute 生命周期</b>测试。
 *
 * <p>要守的不变量：握手鉴权成功（ticket 模式与 dev token fallback 模式）必须同时挂上
 * 原始 token（{@link ImChannelAttributes#TOKEN}）与续签节流时间戳
 * （{@link ImChannelAttributes#LAST_SA_TOKEN_RENEW_AT}）—— 二者是
 * {@code ImSessionKeepAliveService} 在 WS 存活期间续签的输入；
 * 鉴权失败不得挂任何 attr。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImWebSocketAuthHandlerTest {

    private static final String TOKEN = "tok-abc";

    @Mock
    private uno.acloud.im.config.ImTokenAuthService tokenAuthService;
    @Mock
    private WsTicketService ticketService;

    private ImWebSocketAuthHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ImWebSocketAuthHandler(tokenAuthService, ticketService, "dev");
    }

    private FullHttpRequest handshakeRequest(String token) {
        FullHttpRequest request = mockRequest();
        request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, "Bearer, " + token);
        return request;
    }

    private FullHttpRequest mockRequest() {
        FullHttpRequest request = org.mockito.Mockito.mock(FullHttpRequest.class);
        io.netty.handler.codec.http.HttpHeaders headers =
                new io.netty.handler.codec.http.DefaultHttpHeaders();
        lenient().when(request.headers()).thenReturn(headers);
        lenient().when(request.method()).thenReturn(HttpMethod.GET);
        lenient().when(request.protocolVersion()).thenReturn(HttpVersion.HTTP_1_1);
        lenient().when(request.uri()).thenReturn("/ws");
        lenient().when(request.retain()).thenReturn(request);
        return request;
    }

    @Test
    void ticket模式握手成功后挂上token与节流时间戳() {
        when(ticketService.resolveAndConsumeTicket(TOKEN))
                .thenReturn(Optional.of(new WsTicketService.TicketInfo(7L, TOKEN)));
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        handler.channelRead0(channel.pipeline().firstContext(), handshakeRequest(TOKEN));

        assertEquals(7L, channel.attr(ImChannelAttributes.USER_ID).get());
        assertEquals("Bearer " + TOKEN, channel.attr(ImChannelAttributes.AUTHORIZATION).get());
        assertEquals(TOKEN, channel.attr(ImChannelAttributes.TOKEN).get(),
                "握手成功必须挂原始 token，供 WS 存活期间续签");
        AtomicLong lastRenewAt = channel.attr(ImChannelAttributes.LAST_SA_TOKEN_RENEW_AT).get();
        assertNotNull(lastRenewAt, "握手成功必须初始化续签节流时间戳");
        assertEquals(0L, lastRenewAt.get(), "初始节流时间戳应为 0（表示从未续签）");
    }

    @Test
    void dev环境token降级模式握手成功后同样挂上token() {
        when(ticketService.resolveAndConsumeTicket(anyString())).thenReturn(Optional.empty());
        when(tokenAuthService.resolveUserIdByToken(TOKEN)).thenReturn(42L);
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        handler.channelRead0(channel.pipeline().firstContext(), handshakeRequest(TOKEN));

        assertEquals(42L, channel.attr(ImChannelAttributes.USER_ID).get());
        assertEquals(TOKEN, channel.attr(ImChannelAttributes.TOKEN).get());
        assertNotNull(channel.attr(ImChannelAttributes.LAST_SA_TOKEN_RENEW_AT).get());
    }

    @Test
    void prod环境无票据握手失败时不挂任何attr() {
        ImWebSocketAuthHandler prodHandler = new ImWebSocketAuthHandler(tokenAuthService, ticketService, "prod");
        when(ticketService.resolveAndConsumeTicket(anyString())).thenReturn(Optional.empty());
        EmbeddedChannel channel = new EmbeddedChannel(prodHandler);

        prodHandler.channelRead0(channel.pipeline().firstContext(), handshakeRequest(TOKEN));

        assertNull(channel.attr(ImChannelAttributes.TOKEN).get(), "鉴权失败不得挂 token attr");
        assertNull(channel.attr(ImChannelAttributes.LAST_SA_TOKEN_RENEW_AT).get());
        assertNull(channel.attr(ImChannelAttributes.USER_ID).get());
    }
}
