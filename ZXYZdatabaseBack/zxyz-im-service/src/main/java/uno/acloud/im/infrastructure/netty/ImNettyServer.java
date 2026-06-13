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
import uno.acloud.im.config.ImNettyProperties;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "im.netty", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ImNettyServer {

    private final ImNettyProperties properties;
    private final ImWebSocketAuthHandler authHandler;
    private final ImWebSocketFrameHandler webSocketFrameHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ImNettyServer(ImNettyProperties properties,
                         ImWebSocketAuthHandler authHandler,
                         ImWebSocketFrameHandler webSocketFrameHandler) {
        this.properties = properties;
        this.authHandler = authHandler;
        this.webSocketFrameHandler = webSocketFrameHandler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

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
                                .addLast(new HttpObjectAggregator(65536))
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
