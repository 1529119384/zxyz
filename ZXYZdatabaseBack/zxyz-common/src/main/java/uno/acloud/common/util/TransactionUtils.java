package uno.acloud.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务后置动作工具 —— 「提交后再做副作用（远程调用 / MQ / 推送）」的<b>唯一写法</b>。
 *
 * <p>为什么要有这个类：把远程调用放在事务内会<b>长时间占用数据库连接</b>（连接池被慢下游拖死）；
 * 而放到 {@code afterCommit} 又必须处理「事务已提交、回调抛异常」这个容易做错的边界。
 * 本仓历史上同一个语义被手写了多份（im-service 6 处、file-service 3 处调用点），
 * 有的只记日志、有的会让异常继续冒泡 ⇒ 行为在服务之间不一致。本类把该语义收敛到一处。</p>
 *
 * <p><b>为什么必须吞异常</b>：afterCommit 触发时事务已经提交，业务结果已成事实；
 * 若让回调异常冒泡，调用方会把「已经成功」的操作当成失败返回 500
 * （典型场景：文件删除已落库，只是变更通知发不出去），用户看到报错后重试，
 * 反而制造重复操作与状态误判。故此处统一 catch + ERROR 日志（保留可观测性），
 * 由日志/告警暴露问题，而不是污染业务返回值。</p>
 *
 * <p><b>与 {@link TransactionHelper} 的分工</b>：{@code TransactionHelper} 管
 * 「<i>怎么开一个新事务</i>」（{@code TransactionTemplate} 的薄封装，是 {@code @Component}）；
 * 本类是纯静态工具，管「<i>当前事务提交之后做什么</i>」。两者都在本包内，便于一起检索。</p>
 *
 * <p><b>与调用方的约定</b>：传入的动作<b>可以</b>做远程 I/O；若该动作内部还有多个互不影响的
 * 远程调用，请<b>各自再套一层 try/catch</b>并带上可人工补偿的业务主键
 * （teamId / userId / requestId / invitationId），这样一次失败不会掩盖其余动作的结果
 * —— 参见 {@code JoinRequestService.approveJoinRequest} 的既有范式。</p>
 */
public final class TransactionUtils {

    private static final Logger log = LoggerFactory.getLogger(TransactionUtils.class);

    private TransactionUtils() {
    }

    /**
     * 若当前线程有活动事务，则把 {@code action} 注册为提交后回调；否则退化为同步执行。
     * 两条路径的语义一致：<b>失败只记 ERROR 日志，不冒泡</b>。
     */
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
