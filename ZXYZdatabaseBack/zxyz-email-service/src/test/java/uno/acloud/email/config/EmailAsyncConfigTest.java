package uno.acloud.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-14（2026-10-03）：邮件发送线程池的拒绝策略必须是 Abort（拒绝并抛出），
 * 不得是 CallerRuns —— CallerRuns 会让 HTTP 请求线程在队列满时亲自执行 SMTP 发送
 * （TLS 握手秒级），批量场景下把接口延迟放大数十倍。
 * 改为 Abort 后：记录已落库为 PENDING，由 EmailRetryTask 定时兜底，投递不丢。
 */
class EmailAsyncConfigTest {

    @Test
    void 拒绝策略必须是Abort而不是CallerRuns() throws Exception {
        Executor executor = new EmailAsyncConfig().emailTaskExecutor();

        // ThreadPoolTaskExecutor 在 initialize 后其底层 handler 可通过 getThreadPoolExecutor 反射断言，
        // 这里用行为断言更直接： saturate 后提交应被拒绝（抛 RejectedExecutionException）
        ThreadPoolExecutor pool;
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            pool = taskExecutor.getThreadPoolExecutor();
            assertTrue(pool.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy,
                    "拒绝策略必须为 AbortPolicy（当前：" + pool.getRejectedExecutionHandler().getClass().getSimpleName() + "）");
        } else {
            throw new IllegalStateException("emailTaskExecutor 应为 ThreadPoolTaskExecutor");
        }
    }

    @Test
    void 队列饱和时提交应被拒绝而不是回落到调用线程() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new EmailAsyncConfig().emailTaskExecutor();
        try {
            // 占满 maxPoolSize(8) + queueCapacity(1000) 后，下一个提交必须被 Abort 拒绝
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 8 + 1000; i++) {
                executor.execute(() -> {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        } finally {
            // 释放占位任务并关闭
            executor.getThreadPoolExecutor().shutdownNow();
        }
    }
}
