package uno.acloud.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TransactionUtils} 的契约测试。
 *
 * <p>这个类被全仓当作「提交后再做副作用」的<b>唯一写法</b>使用（im-service 6 处、file-service 9 处调用点），
 * 所以三条语义必须被钉死，任何一条被改动都是跨服务的静默行为变化：</p>
 * <ol>
 *   <li><b>有活动事务同步 ⇒ 延迟到提交后</b>（提交前绝不执行）；</li>
 *   <li><b>无活动事务 ⇒ 立即同步执行</b>（不抛 {@code IllegalStateException}）；</li>
 *   <li><b>动作抛异常一律吞掉</b>，只记 ERROR 日志 —— 这正是本类存在的理由：
 *       事务已提交时把异常冒泡出去，会让「已经成功」的操作对用户返回 500。</li>
 * </ol>
 *
 * <p>注意本类<b>不</b>启动 Spring 上下文：{@code TransactionSynchronizationManager} 是纯 ThreadLocal，
 * 用 {@code initSynchronization()} 就能复现真实的「事务进行中」状态。</p>
 */
class TransactionUtilsTest {

    @AfterEach
    void clearThreadLocals() {
        // 同步注册表是 ThreadLocal；不清会把状态泄漏给同线程的下一个用例
        TransactionSynchronizationManager.clear();
    }

    // ==================== 语义 1：有活动事务 ⇒ 延迟到提交后 ====================

    @Test
    void runAfterCommit_withActiveSynchronization_doesNotRunBeforeCommit() {
        AtomicInteger hits = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.runAfterCommit(hits::incrementAndGet);

            assertEquals(0, hits.get(),
                    "提交前绝不能执行 —— 否则远程 I/O 又回到了事务内，本类就失去意义了");

            invokeAfterCommitOnRegisteredSynchronizations();

            assertEquals(1, hits.get(), "提交后必须恰好执行一次");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runAfterCommit_withActiveSynchronization_registersExactlyOneSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.runAfterCommit(() -> {
            });

            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(),
                    "一次调用只注册一个回调；注册多个会让同一个副作用被重复执行");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== 语义 2：无活动事务 ⇒ 立即执行且不抛 ====================

    @Test
    void runAfterCommit_withoutActiveSynchronization_runsImmediately() {
        AtomicInteger hits = new AtomicInteger();

        assertDoesNotThrow(() -> TransactionUtils.runAfterCommit(hits::incrementAndGet));

        assertEquals(1, hits.get(),
                "无事务时应退化为同步执行（而不是让 registerSynchronization 抛 IllegalStateException）");
    }

    @Test
    void runAfterCommit_judgesBySynchronizationActiveNotActualTransactionActive() {
        // 判据刻意与迁移前的 file-service 版本保持一致：看 isSynchronizationActive()。
        // 生产路径上二者同时为真（事务管理器开启事务时必然 initSynchronization），
        // 此用例只钉住「两者不一致时按哪个判据走」，避免后人顺手改成另一个而成批改变行为。
        AtomicInteger hits = new AtomicInteger();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            TransactionUtils.runAfterCommit(hits::incrementAndGet);
            assertEquals(1, hits.get(),
                    "只有 actualTransactionActive（未 initSynchronization）时走同步分支");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    // ==================== 语义 3：异常一律吞掉 ====================

    @Test
    void runAfterCommit_withoutTransaction_swallowsActionException() {
        AtomicInteger reached = new AtomicInteger();

        assertDoesNotThrow(() -> TransactionUtils.runAfterCommit(() -> {
            throw new IllegalStateException("模拟远程调用失败");
        }));

        assertEquals(0, reached.get());
    }

    @Test
    void runAfterCommit_afterCommit_swallowsActionException() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.runAfterCommit(() -> {
                throw new IllegalStateException("模拟提交后远程调用失败");
            });

            // 关键断言：afterCommit 阶段抛异常若冒泡，会把「事务已提交」的操作对外报成失败
            assertDoesNotThrow(this::invokeAfterCommitOnRegisteredSynchronizations);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void invokeAfterCommitOnRegisteredSynchronizations() {
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization synchronization : synchronizations) {
            synchronization.afterCommit();
        }
    }

    // ==================== 重载：带业务主键的 context 不得改变任何一条语义 ====================

    @Test
    void runAfterCommitWithContext_keepsDeferSemantics() {
        AtomicInteger hits = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            TransactionUtils.runAfterCommit("成员创建后发布事件 teamId=10, userId=2", hits::incrementAndGet);

            assertEquals(0, hits.get(), "带 context 的重载必须与不带 context 时一样：提交前不执行");
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size(),
                    "带 context 也只注册一个回调");

            invokeAfterCommitOnRegisteredSynchronizations();

            assertEquals(1, hits.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void runAfterCommitWithContext_stillSwallowsActionException() {
        // 这是本次新增重载的**唯一目的**：让调用方既能带上可人工补偿的业务主键，
        // 又不必自己写 try/catch —— 若这里会抛，调用方就又被迫回到「要日志还是要不冒泡」的二选一。
        assertDoesNotThrow(() -> TransactionUtils.runAfterCommit(
                "逻辑删除后清理分享条目 fileIds(size)=3",
                () -> {
                    throw new IllegalStateException("模拟远程调用失败");
                }));
    }
}
