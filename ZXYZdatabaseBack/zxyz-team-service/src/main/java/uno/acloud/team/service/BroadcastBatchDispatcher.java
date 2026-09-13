package uno.acloud.team.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 全站广播的分批派发器（审计 L10）。
 *
 * <h2>为什么需要它</h2>
 * <p>「全站广播」（系统消息 / 系统邮件）的原实现是<strong>把全站用户塞进一个请求</strong>：
 * {@code getAllUserIds()} 一次性拉回全部 id，紧接着 {@code sendBatch(全部id, ...)} 把整份
 * 列表放进<strong>一个 HTTP body</strong>。下游 im-service 收到后会在<strong>一个事务里</strong>
 * 逐条插入通知 —— 用户量一上来，这条链路会同时踩三颗雷：</p>
 * <ol>
 *   <li><strong>请求体过大</strong>：单个 JSON body 随用户数线性膨胀，可能直接被网关/容器挡掉；</li>
 *   <li><strong>单个长事务</strong>：下游一个事务插 N 条，锁持有时间与回滚代价都随 N 线性增长；</li>
 *   <li><strong>无法部分成功</strong>：整批一失败就是全站都没收到，且没有任何中间进度可观测。</li>
 * </ol>
 * <p>分批之后，单批的请求体与事务规模都被钉在 {@code batchSize} 这个常数上，
 * 下游的压力不再随用户总数增长。</p>
 *
 * <h2>为什么放在调用方（team-service）而不是下游</h2>
 * <p>放在下游只能治「单个事务过大」，治不了「请求体过大」—— 因为 id 列表仍然是
 * 一次性发过去的。只有在<strong>发起方</strong>切批，两个问题才一起消失。同时这样不引入
 * 新的跨服务契约（无需新增分页接口），也就不存在「team-service 先于 user-service 部署
 * 就会静默发不出去」的部署顺序风险。</p>
 *
 * <h2>已知的、有意留下的边界</h2>
 * <p>本类只解决<strong>单批规模</strong>，不改变「广播跑在管理端 HTTP 请求线程里、同步等待」
 * 这个事实 —— 用户数极大时请求总耗时仍会随批数增长（见 {@link #dispatch} 结束时的耗时日志）。
 * 彻底解法是把广播改成异步任务（立即返回、后台派发），那是一次 API 语义变更，
 * 不在 L10 口径内，已记入审计文档作为后续项。</p>
 */
@Slf4j
@Component
public class BroadcastBatchDispatcher {

    /**
     * 单批条数硬上限。
     *
     * <p>存在的意义：配置写错（例如误写成 1000000）会把「分批」悄悄还原成「一次性全量」，
     * 而那种退化<strong>不会有任何报错</strong>。把上限钉死后，配置错误最多只是分批粒度变粗。</p>
     */
    public static final int MAX_BATCH_SIZE = 5000;

    private final int batchSize;
    private final long batchIntervalMs;

    public BroadcastBatchDispatcher(
            @Value("${app.broadcast.batch-size:500}") int batchSize,
            @Value("${app.broadcast.batch-interval-ms:200}") long batchIntervalMs) {
        if (batchSize < 1) {
            log.warn("app.broadcast.batch-size={} 非法（须 ≥ 1），回落到 1", batchSize);
            batchSize = 1;
        } else if (batchSize > MAX_BATCH_SIZE) {
            log.warn("app.broadcast.batch-size={} 超过上限 {}，已钳制（否则分批失去意义）",
                    batchSize, MAX_BATCH_SIZE);
            batchSize = MAX_BATCH_SIZE;
        }
        if (batchIntervalMs < 0L) {
            log.warn("app.broadcast.batch-interval-ms={} 非法（须 ≥ 0），回落到 0", batchIntervalMs);
            batchIntervalMs = 0L;
        }
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;
    }

    /** 当前生效的单批条数（供测试与日志使用）。 */
    public int batchSize() {
        return batchSize;
    }

    /** 当前生效的批间隔毫秒数（供测试与日志使用）。 */
    public long batchIntervalMs() {
        return batchIntervalMs;
    }

    /**
     * 把 {@code items} 切成若干批依次交给 {@code batchAction}，批与批之间停顿
     * {@link #batchIntervalMs} 毫秒。
     *
     * <p>每批传入的列表都是<strong>独立拷贝</strong>（不是 {@code subList} 视图）——
     * 调用方若在 lambda 里保留引用或做修改，不会反过来影响后续批次。</p>
     *
     * <p>停顿放在「批之间」而非「每批之后」：最后一批派发完立即返回，不做无意义的等待。</p>
     *
     * @return 实际派发的批数；{@code items} 为空时返回 0（此时不会调用 {@code batchAction}）
     */
    public <T> int dispatch(List<T> items, Consumer<List<T>> batchAction) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int total = items.size();
        long startedAt = System.nanoTime();
        int batches = 0;
        int dispatched = 0;
        for (int from = 0; from < total; from += batchSize) {
            int to = Math.min(from + batchSize, total);
            List<T> batch = new ArrayList<>(items.subList(from, to));
            batchAction.accept(batch);
            batches++;
            dispatched = to;
            if (to < total && batchIntervalMs > 0L && !sleepQuietly()) {
                log.warn("分批派发被中断：已派发 {}/{} 条，剩余 {} 条未派发",
                        to, total, total - to);
                break;
            }
        }
        if (batches > 1) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            log.info("分批派发完成：{} 条 / {} 批（每批 {}、间隔 {}ms），耗时 {}ms",
                    dispatched, batches, batchSize, batchIntervalMs, elapsedMs);
        }
        return batches;
    }

    /**
     * 批间停顿。
     *
     * <p>刻意声明为包级可见而非 {@code private}：单测通过覆写它来<strong>确定性地</strong>断言
     * 「一共停了几次」（这批之间该停 N-1 次），而不是靠测耗时 —— 计时断言在负载高的 CI 上会随机失败，
     * 属于典型的 flaky 测试。</p>
     *
     * @return 是否完整睡满；被中断时返回 false 并恢复中断标记（不吞掉中断）。
     */
    boolean sleepQuietly() {
        try {
            Thread.sleep(batchIntervalMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
