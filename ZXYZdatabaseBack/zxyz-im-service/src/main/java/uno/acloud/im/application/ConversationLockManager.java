package uno.acloud.im.application;

import org.springframework.stereotype.Component;
import uno.acloud.common.lock.DistributedLockTemplate;

import java.util.function.Supplier;

/**
 * 会话域分布式锁入口。
 *
 * <p><b>P1-2</b>：锁实现已收敛到 {@link DistributedLockTemplate}。本类现在只负责会话域自己的
 * 两件事 —— 锁键前缀与租约口径（等待 2 秒 / 租约 30 秒），不再自带一份解锁实现。
 * 此前它内部那份实现与 team-service 的 6 段内联锁块等价，两处各自维护
 * 「{@code acquired && isHeldByCurrentThread()} 才解锁」这条语义，任一处写错都只在并发下偶发暴露。</p>
 */
@Component
public class ConversationLockManager {

    private static final String LOCK_PREFIX = "zxyz:im:lock:conversation:";
    private static final long WAIT_SECONDS = 2L;
    private static final long LEASE_SECONDS = 30L;

    private final DistributedLockTemplate lockTemplate;

    public ConversationLockManager(DistributedLockTemplate lockTemplate) {
        this.lockTemplate = lockTemplate;
    }

    /**
     * 持有 {@code LOCK_PREFIX + key} 执行 {@code supplier}。
     *
     * @param key 会话域子键（如 {@code conversation-message:123}），前缀由本类补齐
     */
    public <T> T withLock(String key, Supplier<T> supplier) {
        return lockTemplate.withLock(LOCK_PREFIX + key, WAIT_SECONDS, LEASE_SECONDS, supplier);
    }
}
