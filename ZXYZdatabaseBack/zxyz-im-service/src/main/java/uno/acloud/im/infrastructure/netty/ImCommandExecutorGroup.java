package uno.acloud.im.infrastructure.netty;

import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutorGroup;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * WebSocket 业务线程组（B-6）。
 *
 * <p><b>为什么必须有这一层</b>：{@code channelRead0 → ImCommandDispatcher.dispatch} 整条链路
 * 原本同步跑在 Netty 的 EventLoop 线程上，而其内部含 DB 事务、Redis 幂等/分布式锁
 * （{@code tryLock} 最长等 2 秒）与 {@code FileCardClient} 的 HTTP 调用（readTimeout 10 秒）。
 * EventLoop 线程被占住时，<b>同一条 EventLoop 上所有 channel</b> 的读写都会停摆 ——
 * 包括 PING/PONG 心跳与集群推送（{@code pushLocal} 也在 EventLoop 上 writeAndFlush）。
 * 一个慢消息即可造成成片连接假死。</p>
 *
 * <p><b>为什么用 {@code DefaultEventExecutorGroup} 挂在 pipeline 上，而不是在 handler 里
 * 自己 {@code execute}</b>：Netty 保证<b>同一个 Channel 的同一个 handler 始终由该 group 中
 * 固定的那一个 EventExecutor 执行</b>。因此「同一连接内的消息按到达顺序处理」这条语义
 * 得以保留；换成共享线程池自行提交就会打乱这个顺序（客户端 {@code sendQueue} 只序列化
 * 发送动作、不等 ACK，两帧可以同时在途）。</p>
 *
 * <p>线程数是固定常量而非热配置：它属于部署形态（CPU/连接数）而非业务参数，
 * 且改它需要重启才安全 —— 运行期缩容会在旧线程上留下在途任务。</p>
 */
@Slf4j
@Component
public class ImCommandExecutorGroup {

    /**
     * 业务线程数。取值口径：远小于 EventLoop 线程数（默认 ≈ 2×CPU），
     * 因为这里的任务是「每连接串行」的 IO 密集型工作，不是 CPU 密集；
     * 8 个线程足以把 EventLoop 从阻塞中解放，又不会把下游 DB/Redis 连接池打满。
     */
    private static final int BUSINESS_THREADS = 8;

    private final DefaultEventExecutorGroup group;

    public ImCommandExecutorGroup() {
        this.group = new DefaultEventExecutorGroup(
                BUSINESS_THREADS, new DefaultThreadFactory("im-ws-biz", true));
        log.info("IM WebSocket 业务线程组已创建: threads={}", BUSINESS_THREADS);
    }

    /** pipeline 装配用：把 handler 绑到本线程组上。 */
    public EventExecutorGroup eventExecutorGroup() {
        return group;
    }

    @PreDestroy
    public void shutdown() {
        // graceful shutdown：等在途消息处理完再退出，避免关停时正在落库的消息被硬中断。
        group.shutdownGracefully();
        log.info("IM WebSocket 业务线程组已停止");
    }
}
