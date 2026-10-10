package uno.acloud.im.infrastructure.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.im.application.ImCommandDispatcher;
import uno.acloud.im.application.ImCommandResult;
import uno.acloud.im.application.ImSessionKeepAliveService;
import uno.acloud.im.application.SaTokenRenewDelegate;
import uno.acloud.im.infrastructure.netty.protocol.ImEnvelopeFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImWebSocketFrameHandler} 的<b>会话续签触发点</b>测试。
 *
 * <p>要守的不变量：每条入站帧（含 PING 心跳）处理入口都会尝试续签底层
 * Sa-Token 会话（节流生效：同一连接至多每 5 分钟一次）；attr 未挂 token 时
 * 静默跳过；底层续签异常不影响消息主链路（PONG / MESSAGE_ACK 照常写回）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImWebSocketFrameHandlerTest {

    private static final String TOKEN = "tok-123";

    @Mock
    private ImConnectionRegistry connectionRegistry;
    @Mock
    private ImCommandDispatcher commandDispatcher;
    @Mock
    private SaTokenRenewDelegate renewDelegate;

    private ImSessionKeepAliveService keepAliveService;
    private ImWebSocketFrameHandler handler;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        keepAliveService = new ImSessionKeepAliveService(renewDelegate);
        handler = new ImWebSocketFrameHandler(new ObjectMapper(), new ImEnvelopeFactory(new ObjectMapper()),
                connectionRegistry, commandDispatcher, keepAliveService);
        channel = new EmbeddedChannel(handler);
    }

    /** 模拟「握手已完成」的连接：attr 已挂 token 与节流时间戳。 */
    private void attachHandshakeAttrs() {
        channel.attr(ImChannelAttributes.USER_ID).set(42L);
        channel.attr(ImChannelAttributes.TOKEN).set(TOKEN);
        channel.attr(ImChannelAttributes.AUTHORIZATION).set("Bearer " + TOKEN);
        channel.attr(ImChannelAttributes.LAST_SA_TOKEN_RENEW_AT).set(new AtomicLong(0L));
    }

    private String readOutboundText() {
        TextWebSocketFrame frame = channel.readOutbound();
        return frame == null ? null : frame.text();
    }

    @Test
    void ping帧触发一次底层续签并照常回pong() {
        attachHandshakeAttrs();

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r1\"}"));

        verify(renewDelegate, times(1)).updateLastActiveToNow(TOKEN);
        String out = readOutboundText();
        assertNotNull(out);
        assertTrue(out.contains("\"PONG\""), "PING 应照常得到 PONG 响应，实际: " + out);
    }

    @Test
    void 同一连接五分钟内的第二条帧被节流不再续签() {
        attachHandshakeAttrs();

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r1\"}"));
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r2\"}"));

        verify(renewDelegate, times(1)).updateLastActiveToNow(TOKEN);
        // 两条 PING 都必须得到响应（节流只挡续签，不挡消息）
        assertNotNull(readOutboundText());
        assertNotNull(readOutboundText());
    }

    @Test
    void 业务消息帧同样触发续签且命令链路不受影响() {
        attachHandshakeAttrs();
        when(commandDispatcher.dispatch(any()))
                .thenReturn(new ImCommandResult("r1", "c1", 7L, 100L));

        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"SEND_TEXT\",\"requestId\":\"r1\",\"clientMessageId\":\"c1\","
                        + "\"conversationId\":7,\"payload\":{\"content\":\"hi\"}}"));

        verify(renewDelegate, times(1)).updateLastActiveToNow(TOKEN);
        String out = readOutboundText();
        assertNotNull(out);
        assertTrue(out.contains("MESSAGE_ACK"), "业务消息应照常得到 ACK，实际: " + out);
    }

    @Test
    void channel未挂token时不续签也不抛异常() {
        // 模拟未认证连接：只挂 USER_ID，不挂 TOKEN / LAST_SA_TOKEN_RENEW_AT
        channel.attr(ImChannelAttributes.USER_ID).set(42L);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r1\"}"));

        verify(renewDelegate, never()).updateLastActiveToNow(anyString());
        String out = readOutboundText();
        assertNotNull(out, "未认证连接的 PING 也应照常回 PONG（续签静默跳过）");
        assertTrue(out.contains("\"PONG\""));
    }

    @Test
    void 底层续签抛异常时消息主链路不受影响() {
        attachHandshakeAttrs();
        doThrow(new RuntimeException("redis down")).when(renewDelegate).updateLastActiveToNow(TOKEN);

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r1\"}"));

        String out = readOutboundText();
        assertNotNull(out, "续签失败必须静默，PONG 照常写回");
        assertTrue(out.contains("\"PONG\""));
        assertTrue(channel.isOpen(), "续签异常不应关闭连接");
    }

    @Test
    void 握手时挂的节流时间戳随帧处理推进() {
        attachHandshakeAttrs();

        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"PING\",\"requestId\":\"r1\"}"));

        AtomicLong lastRenewAt = channel.attr(ImChannelAttributes.LAST_SA_TOKEN_RENEW_AT).get();
        assertNotNull(lastRenewAt);
        assertTrue(lastRenewAt.get() > 0L, "首次续签后节流时间戳应被更新为当前时间");
    }

    @Test
    void 命令请求携带握手时记录的authorization() {
        attachHandshakeAttrs();
        when(commandDispatcher.dispatch(any()))
                .thenReturn(new ImCommandResult("r1", "c1", 7L, 100L));

        channel.writeInbound(new TextWebSocketFrame(
                "{\"type\":\"SEND_TEXT\",\"requestId\":\"r1\",\"clientMessageId\":\"c1\","
                        + "\"conversationId\":7,\"payload\":{\"content\":\"hi\"}}"));

        org.mockito.ArgumentCaptor<uno.acloud.im.application.ImCommandRequest> captor =
                org.mockito.ArgumentCaptor.forClass(uno.acloud.im.application.ImCommandRequest.class);
        verify(commandDispatcher).dispatch(captor.capture());
        assertEquals(42L, captor.getValue().userId());
        assertEquals("Bearer " + TOKEN, captor.getValue().authorization(),
                "命令请求必须携带握手时记录的 authorization");
    }
}
