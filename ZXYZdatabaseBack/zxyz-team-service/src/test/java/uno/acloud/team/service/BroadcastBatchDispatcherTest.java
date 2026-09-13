package uno.acloud.team.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BroadcastBatchDispatcher} 单元测试（审计 L10 配套）。
 *
 * <p>这里要守住的是「分批」这个契约本身 —— 它坏了不会报错，只会让全站广播悄悄退回
 * 「一个请求装下所有人」：那正是 L10 要修的问题，且症状只会在用户量上来之后才显现。</p>
 */
class BroadcastBatchDispatcherTest {

    /** 覆写 {@link #sleepQuietly} 以便确定性地数「停了几次」，避免用耗时断言（CI 上会 flaky）。 */
    private static class CountingDispatcher extends BroadcastBatchDispatcher {
        private final AtomicInteger sleeps = new AtomicInteger();

        CountingDispatcher(int batchSize, long intervalMs) {
            super(batchSize, intervalMs);
        }

        @Override
        boolean sleepQuietly() {
            sleeps.incrementAndGet();
            return true;
        }
    }

    @Test
    void dispatch_shouldSplitIntoBatchesOfConfiguredBatchSize() {
        BroadcastBatchDispatcher dispatcher = new BroadcastBatchDispatcher(500, 0L);
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            items.add(i);
        }

        List<Integer> batchSizes = new ArrayList<>();
        int batches = dispatcher.dispatch(items, batch -> batchSizes.add(batch.size()));

        assertEquals(3, batches);
        assertEquals(List.of(500, 500, 201), batchSizes, "最后一批应是余数，而不是被补齐或丢弃");
    }

    @Test
    void dispatch_shouldNotInvokeActionForEmptyOrNullInput() {
        BroadcastBatchDispatcher dispatcher = new BroadcastBatchDispatcher(10, 0L);
        AtomicInteger calls = new AtomicInteger();

        assertEquals(0, dispatcher.dispatch(List.of(), batch -> calls.incrementAndGet()));
        assertEquals(0, dispatcher.dispatch(null, batch -> calls.incrementAndGet()));
        assertEquals(0, calls.get(), "空输入不该触发任何下游调用");
    }

    @Test
    void dispatch_shouldPassIndependentCopiesSoCallerCannotCorruptSourceOrLaterBatches() {
        BroadcastBatchDispatcher dispatcher = new BroadcastBatchDispatcher(2, 0L);
        List<Integer> items = new ArrayList<>(List.of(1, 2, 3, 4));
        List<Integer> receivedSizes = new ArrayList<>();

        dispatcher.dispatch(items, batch -> {
            receivedSizes.add(batch.size());
            batch.set(0, -1); // 篡改本批：若传的是 subList 视图，会连带改坏源列表与后续批次
        });

        assertEquals(List.of(2, 2), receivedSizes);
        assertEquals(List.of(1, 2, 3, 4), items, "调用方篡改批次不得影响源列表");
    }

    @Test
    void dispatch_shouldSleepBetweenBatchesOnlyAndNeverAfterTheLastOne() {
        // N 批之间只该停 N-1 次；最后一批派发完立即返回（再做无意义等待会白拖长管理端请求）
        CountingDispatcher dispatcher = new CountingDispatcher(2, 200L);
        List<Integer> items = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));

        int batches = dispatcher.dispatch(items, batch -> {
        });

        assertEquals(3, batches);
        assertEquals(2, dispatcher.sleeps.get(), "3 批应只在批间停 2 次");

        // 单批场景一次都不该停
        CountingDispatcher single = new CountingDispatcher(500, 200L);
        assertEquals(1, single.dispatch(List.of(1, 2, 3), batch -> {
        }));
        assertEquals(0, single.sleeps.get(), "单批不该有任何停顿");
    }

    @Test
    void dispatch_shouldStopEarlyAndKeepInterruptFlagWhenSleepIsInterrupted() {
        BroadcastBatchDispatcher dispatcher = new BroadcastBatchDispatcher(1, 10L) {
            @Override
            boolean sleepQuietly() {
                Thread.currentThread().interrupt();
                return false;
            }
        };
        AtomicInteger dispatched = new AtomicInteger();

        try {
            int batches = dispatcher.dispatch(List.of(1, 2, 3), batch -> dispatched.incrementAndGet());

            // 第 1 批发出后被中断 ⇒ 停止后续派发，并如实回报已派发批数
            assertEquals(1, batches);
            assertEquals(1, dispatched.get());
            assertTrue(Thread.interrupted(), "中断标记必须被恢复，不能吞掉（否则上层无法感知被中断）");
        } finally {
            Thread.interrupted(); // 清理，避免影响后续用例
        }
    }

    @Test
    void constructor_shouldClampIllegalConfigInsteadOfSilentlyDisablingBatching() {
        // 0 / 负数：回落到 1（仍能工作，而不是抛异常让服务起不来）
        assertEquals(1, new BroadcastBatchDispatcher(0, 0L).batchSize());
        assertEquals(1, new BroadcastBatchDispatcher(-5, 0L).batchSize());
        // 超大值：钳到硬上限，否则配置笔误会把「分批」静默还原成「一次性全量」
        assertEquals(BroadcastBatchDispatcher.MAX_BATCH_SIZE,
                new BroadcastBatchDispatcher(1_000_000, 0L).batchSize());
        // 负间隔回落到 0
        assertEquals(0L, new BroadcastBatchDispatcher(10, -1L).batchIntervalMs());
        // 合法值原样保留
        assertEquals(10, new BroadcastBatchDispatcher(10, 0L).batchSize());
        assertEquals(200L, new BroadcastBatchDispatcher(10, 200L).batchIntervalMs());
    }
}
