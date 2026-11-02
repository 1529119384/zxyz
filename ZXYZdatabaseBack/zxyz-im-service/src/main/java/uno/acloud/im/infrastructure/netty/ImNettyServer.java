package uno.acloud.im.infrastructure.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import uno.acloud.im.config.ImNettyProperties;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "im.netty", name = "enabled", havingValue = "true", matchIfMissing = true)
// 重启生效（不可加 @RefreshScope）：本类持有运行中的 Netty boss/worker EventLoopGroup 与 serverChannel，
// 刷新会重建 Bean 并中断所有 WebSocket 连接；且 app.im.ws.max-content-length 不在 zxyz-dynamic.yml 热更清单内。
public class ImNettyServer {

    private static final int FALLBACK_MAX_CONTENT_LENGTH = 65536;

    private final ImNettyProperties properties;
    private final ImWebSocketAuthHandler authHandler;
    private final ImWebSocketFrameHandler webSocketFrameHandler;
    private final ImCommandExecutorGroup commandExecutorGroup;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final int maxContentLength;

    public ImNettyServer(ImNettyProperties properties,
                         ImWebSocketAuthHandler authHandler,
                         ImWebSocketFrameHandler webSocketFrameHandler,
                         ImCommandExecutorGroup commandExecutorGroup,
                         @Value("${app.im.ws.max-content-length:65536}") int maxContentLength) {
        this.properties = properties;
        this.authHandler = authHandler;
        this.webSocketFrameHandler = webSocketFrameHandler;
        this.commandExecutorGroup = commandExecutorGroup;
        this.maxContentLength = maxContentLength;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    // 66 号二.3（2026-11-02 补）：低延迟小包（IM 文本帧），禁用 Nagle
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    // 66 号二.3：写缓冲水位线 —— 慢消费者（不再读但连接未断）时
                    // Channel.isWritable() 会转 false，业务侧可据此丢弃/断开，避免
                    // 无界内存增长（Netty 默认 high=64KiB，对广播场景偏小）。
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                            new WriteBufferWaterMark(32 * 1024, 256 * 1024))
                    .childHandler(channelInitializer());

            ChannelFuture bindFuture = bootstrap.bind(properties.getPort()).sync();
            serverChannel = bindFuture.channel();
            log.info("IM Netty WebSocket server started on port {}, path {}",
                    properties.getPort(), properties.getWebsocketPath());
        } catch (Exception e) {
            // B-7：绑定失败（端口被占用等）必须当场归还 EventLoop 资源。
            // `start()` 由 ApplicationReadyEvent 触发，监听器抛异常时 Spring **不会**中止启动，
            // 只会记录下来 —— 若这里不主动释放，进程会带着两组空转的 EventLoop 线程继续跑，
            // 而 isRunning() 为 false（健康检查 DOWN）只能靠外部编排重启才回收。
            // 故立即 shutdownGracefully 后原样抛出，让启动「响亮失败」。
            log.error("IM Netty WebSocket server 启动失败（已释放 EventLoop 资源）, port={}",
                    properties.getPort(), e);
            releaseEventLoopGroups();
            throw e;
        }
    }

    /**
     * pipeline 装配。抽成包级方法便于单测直接校验「业务 handler 绑在业务线程组上」（B-6）——
     * 否则这条不变量只能靠读代码保证。
     */
    ChannelInitializer<SocketChannel> channelInitializer() {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                configurePipeline(channel.pipeline());
            }
        };
    }

    /** @see #channelInitializer() */
    void configurePipeline(ChannelPipeline pipeline) {
        int maxContentLength = this.maxContentLength;
        // 66 号二.3（2026-11-02 补）：IdleStateHandler 必须装配在**最前**（编解码之前），
        // 才能观察到原始读事件；0 表示禁用（properties.readerIdleSeconds=0 时退化为旧行为）。
        int readerIdle = properties.getReaderIdleSeconds();
        if (readerIdle > 0) {
            pipeline.addLast(new IdleStateHandler(readerIdle, 0, 0));
        }
        pipeline.addLast(new HttpServerCodec())
                .addLast(new ChunkedWriteHandler())
                .addLast(new HttpObjectAggregator(maxContentLength))
                .addLast(authHandler)
                .addLast(new WebSocketServerProtocolHandler(properties.getWebsocketPath(), "Bearer", true))
                // B-6：业务 handler 交给独立线程组，EventLoop 只做编解码与写回。
                // 同一 Channel 的该 handler 始终由组内固定 executor 执行，
                // 因此「同一连接内消息按序处理」的语义不变。
                .addLast(commandExecutorGroup.eventExecutorGroup(), webSocketFrameHandler)
                // 66 号二.3：空闲事件消费者（IdleStateHandler 只发事件、不会自动关连接）
                .addLast(new ImIdleCloseHandler());
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    @PreDestroy
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        releaseEventLoopGroups();
        log.info("IM Netty WebSocket server stopped");
    }

    private void releaseEventLoopGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }
}
