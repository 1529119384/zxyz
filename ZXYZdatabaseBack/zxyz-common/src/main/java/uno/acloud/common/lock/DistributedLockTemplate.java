package uno.acloud.common.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁的**唯一入口** —— 收敛全仓「抢锁 → 判空 → 执行 → 解锁」这段三段式样板。
 *
 * <p><b>为什么必须集中</b>：修复前 team-service 内有 6 段内联复制的锁块，
 * im-service 另有一份等价实现（{@code ConversationLockManager}）。三处各自书写解锁条件与异常文案，
 * 任一处写错都只在并发下偶发暴露、极难复现。集中后「{@code acquired && isHeldByCurrentThread()}
 * 才解锁」这条语义只有一份实现。</p>
 *
 * <p><b>为什么不加 {@code @Component}</b>：本类依赖 {@code RedissonClient}，而后者由条件自动配置
 * {@code uno.acloud.autoconfig.RedissonAutoConfiguration} 提供。全仓有 9 个服务都
 * {@code @ComponentScan("uno.acloud.common")}；若把本类做成 {@code @Component}，
 * 任何拿不到 {@code RedissonClient} 的服务都会**启动即失败**（比"静默不创建"更糟）。
 * 因此本类改为由该自动配置以 {@code @Bean} 提供，存活条件与 {@code RedissonClient} 严格一致。</p>
 *
 * <p><b>用法约束（生产语义，P1-2 事故根因）</b>：临界区里若含数据库事务，必须让锁
 * <b>包住整个事务</b>（抢锁 → 开事务 → 提交 → 释放），<b>不要</b>在事务已经打开之后再进来抢锁。否则：
 * ① 锁等待期间数据库连接与行锁被连带持有（最长可达 {@code waitSeconds}）；
 * ② 解锁发生在事务提交**之前** ⇒ "先查存在再插入"类不变式对并发方不可见，锁形同虚设。
 * 本类的解锁在 {@code finally} 中、即调用方 lambda 返回之后执行，所以把
 * {@code transactionHelper.execute(...)} 整体放进 lambda 即为正确用法。</p>
 */
public class DistributedLockTemplate {

    /** 抢锁失败文案（与原 team-service / im-service 两处实现逐字一致，集中于此保证口径统一） */
    public static final String MESSAGE_CONCURRENT = "操作过于频繁，请稍后重试";

    /** 抢锁被中断文案 */
    public static final String MESSAGE_INTERRUPTED = "操作被中断";

    private final RedissonClient redissonClient;

    public DistributedLockTemplate(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 有返回值版本：持有 {@code lockKey} 执行 {@code supplier} 并返回其结果。
     *
     * @param lockKey     完整锁键（前缀由调用方决定，各域自行约定）
     * @param waitSeconds 抢锁最长等待秒数（租约口径由调用方保留，不做全局统一）
     * @param leaseSeconds 租约秒数；必须大于临界区最长耗时，否则锁会在事务提交前自动过期
     */
    public <T> T withLock(String lockKey, long waitSeconds, long leaseSeconds, Supplier<T> supplier) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, MESSAGE_CONCURRENT);
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, MESSAGE_INTERRUPTED);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 无返回值版本，避免调用方被迫在 lambda 末尾写 {@code return null}。
     */
    public void withLockVoid(String lockKey, long waitSeconds, long leaseSeconds, Runnable action) {
        withLock(lockKey, waitSeconds, leaseSeconds, () -> {
            action.run();
            return null;
        });
    }
}
