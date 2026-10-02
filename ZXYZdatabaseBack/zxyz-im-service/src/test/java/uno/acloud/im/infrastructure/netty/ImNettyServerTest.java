package uno.acloud.im.infrastructure.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.util.concurrent.EventExecutorGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import uno.acloud.im.config.ImNettyProperties;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ImNettyServer} 的 pipeline 装配与启动失败清理（B-6 / B-7）。
 *
 * <p>B-6 要守的不变量：<b>业务 handler 必须绑在业务线程组上</b>，否则阻塞 IO
 * （DB 事务 + 分布式锁 2s 等待 + HTTP 10s 读超时）会把 EventLoop 连同
 * 同组其它连接的心跳一起拖死。这条不变量原先只能靠读代码保证。</p>
 *
 * <p>B-7 要守的不变量：<b>bind 失败必须归还 EventLoop 线程</b>。
 * {@code start()} 由 ApplicationReadyEvent 触发，监听器抛异常时 Spring 不会中止启动，
 * 不主动释放就会留下两组空转线程。</p>
 */
class ImNettyServerTest {

    private final ImCommandExecutorGroup commandExecutorGroup = new ImCommandExecutorGroup();

    @AfterEach
    void tearDown() {
        commandExecutorGroup.shutdown();
    }

    private ImNettyServer server(int port) {
        ImNettyProperties properties = new ImNettyProperties();
        properties.setPort(port);
        properties.setWebsocketPath("/ws");
        return new ImNettyServer(properties, mock(ImWebSocketAuthHandler.class),
                mock(ImWebSocketFrameHandler.class), commandExecutorGroup, 65536);
    }

    /** 记录一次 pipeline.addLast 调用；group 为 null 表示「不带线程组」的重载。 */
    private record AddCall(EventExecutorGroup group, ChannelHandler handler, String name) {
    }

    private List<AddCall> capturePipelineAdds() {
        List<AddCall> calls = new ArrayList<>();
        ChannelPipeline pipeline = mock(ChannelPipeline.class);
        when(pipeline.addLast(any(ChannelHandler.class))).thenAnswer(invocation -> {
            calls.add(new AddCall(null, invocation.getArgument(0), null));
            return pipeline;
        });
        when(pipeline.addLast(any(EventExecutorGroup.class), any(ChannelHandler.class)))
                .thenAnswer(invocation -> {
                    calls.add(new AddCall(invocation.getArgument(0), invocation.getArgument(1), null));
                    return pipeline;
                });
        server(0).configurePipeline(pipeline);
        return calls;
    }

    // ==================== B-6：业务 handler 绑到独立线程组 ====================

    @Test
    void channelInitializer_bindsFrameHandlerToBusinessExecutorGroup() {
        List<AddCall> calls = capturePipelineAdds();

        List<AddCall> grouped = calls.stream().filter(call -> call.group() != null).toList();
        assertEquals(1, grouped.size(),
                "有且只有业务 frameHandler 应经 addLast(group, handler) 注册，实际 " + grouped.size() + " 处");

        AddCall frameHandlerCall = grouped.get(0);
        assertEquals(commandExecutorGroup.eventExecutorGroup(), frameHandlerCall.group(),
                "业务 frameHandler 必须绑在业务线程组上 —— 否则阻塞 IO 会占死 EventLoop");
        assertTrue(frameHandlerCall.handler() instanceof ImWebSocketFrameHandler,
                "带线程组注册的必须是 ImWebSocketFrameHandler");
    }

    @Test
    void channelInitializer_keepsCodecAndHandshakeOnDefaultEventLoop() {
        List<AddCall> calls = capturePipelineAdds();

        // 鉴权与握手必须留在 EventLoop 上：它们只做 Redis GETDEL / 头解析，
        // 语义上属于「连接建立」而非「业务处理」，换线程反而引入跨线程顺序问题。
        assertTrue(calls.stream().anyMatch(call -> call.handler() instanceof ImWebSocketAuthHandler),
                "鉴权 handler 应保留在默认 EventLoop 上");
        assertTrue(calls.stream().anyMatch(call -> call.handler() instanceof HttpObjectAggregator),
                "应装配 HttpObjectAggregator（聚合 WebSocket 握手帧）");
    }

    // ==================== B-7：bind 失败要归还 EventLoop ====================

    @Test
    void start_whenPortAlreadyBound_releasesEventLoopGroups() throws Exception {
        long eventLoopsBefore = countNettyEventLoopThreads();

        // 先占住一个端口，让真实 bind 失败（不 mock Netty，走真实绑定路径）
        try (java.net.ServerSocket blocker = new java.net.ServerSocket(0)) {
            ImNettyServer failing = server(blocker.getLocalPort());

            assertThrows(Exception.class, failing::start,
                    "端口被占用时 start() 必须抛出，让启动响亮失败");
            assertTrue(!failing.isRunning(), "启动失败后 isRunning() 必须为 false");
        }

        assertTrue(awaitEventLoopCountAtMost(eventLoopsBefore, 5_000),
                "bind 失败后 boss/worker EventLoop 必须被 shutdown，否则进程会带着空转线程继续跑"
                        + "（健康检查 DOWN 却无人回收）");
    }

    private static long countNettyEventLoopThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName() != null && t.getName().startsWith("nioEventLoopGroup"))
                .count();
    }

    /** 等 Netty EventLoop 线程数回落到基线（graceful shutdown 是异步的，需要等待）。 */
    private static boolean awaitEventLoopCountAtMost(long baseline, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (countNettyEventLoopThreads() <= baseline) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
