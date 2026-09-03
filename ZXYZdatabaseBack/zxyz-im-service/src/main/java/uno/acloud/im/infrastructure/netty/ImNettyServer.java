package uno.acloud.im.infrastructure.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
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
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final int maxContentLength;

    public ImNettyServer(ImNettyProperties properties,
                         ImWebSocketAuthHandler authHandler,
                         ImWebSocketFrameHandler webSocketFrameHandler,
                         @Value("${app.im.ws.max-content-length:65536}") int maxContentLength) {
        this.properties = properties;
        this.authHandler = authHandler;
        this.webSocketFrameHandler = webSocketFrameHandler;
        this.maxContentLength = maxContentLength;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        int maxContentLength = this.maxContentLength;

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        channel.pipeline()
                                .addLast(new HttpServerCodec())
                                .addLast(new ChunkedWriteHandler())
                                .addLast(new HttpObjectAggregator(maxContentLength))
                                .addLast(authHandler)
                                .addLast(new WebSocketServerProtocolHandler(properties.getWebsocketPath(), "Bearer", true))
                                .addLast(webSocketFrameHandler);
                    }
                });

        ChannelFuture bindFuture = bootstrap.bind(properties.getPort()).sync();
        serverChannel = bindFuture.channel();
        log.info("IM Netty WebSocket server started on port {}, path {}",
                properties.getPort(), properties.getWebsocketPath());
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    @PreDestroy
    public void shutdown() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        log.info("IM Netty WebSocket server stopped");
    }
}
