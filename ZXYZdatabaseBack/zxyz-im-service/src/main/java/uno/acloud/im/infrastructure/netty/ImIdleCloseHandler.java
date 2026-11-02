package uno.acloud.im.infrastructure.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 66 号二.3（2026-11-02 补）：空闲连接关闭器。
 *
 * <p>{@link io.netty.handler.timeout.IdleStateHandler} 只产生事件，不会自动断开 ——
 * 必须有消费者。放在 pipeline 末尾（业务 handler 之后）：
 * 收到 {@link IdleStateEvent}（读空闲/全空闲）即记录并关闭 Channel，
 * 让半开连接（客户端崩溃、网络中断但 TCP 未 FIN）不会永久占着内存与 fd。</p>
 *
 * <p>只处理空闲事件，其余事件全部透传，不改变任何业务语义。</p>
 */
@Slf4j
public class ImIdleCloseHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle) {
            log.info("IM 连接空闲超时，主动关闭: channel={}, state={}",
                    ctx.channel().id().asShortText(), idle.state());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }
}
