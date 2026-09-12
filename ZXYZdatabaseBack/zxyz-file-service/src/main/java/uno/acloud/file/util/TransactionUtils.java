package uno.acloud.file.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务后置动作工具。
 *
 * <p><b>为什么必须吞异常</b>：afterCommit 触发时事务已经提交，业务结果已成事实；
 * 若让回调异常冒泡，调用方会把「已经成功」的操作当成失败返回 500
 * （典型场景：文件删除已落库，只是变更通知发不出去），用户看到报错后重试，
 * 反而制造重复操作与状态误判。故此处统一 catch + ERROR 日志（保留可观测性），
 * 由日志/告警暴露问题，而不是污染业务返回值。</p>
 */
public final class TransactionUtils {

    private static final Logger log = LoggerFactory.getLogger(TransactionUtils.class);

    private TransactionUtils() {
    }

    public static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(action);
                }
            });
            return;
        }
        // 无活动事务时该动作退化为「同步执行」，但调用方的意图同样是「副作用不影响主流程」，
        // 因此两条路径语义保持一致：失败只记日志。
        safeRun(action);
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("事务后置动作执行失败（业务结果已提交，不影响本次操作返回）", e);
        }
    }
}
