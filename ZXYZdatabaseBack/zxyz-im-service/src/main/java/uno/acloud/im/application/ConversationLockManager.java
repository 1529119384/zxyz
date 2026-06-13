package uno.acloud.im.application;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class ConversationLockManager {

    private static final String LOCK_PREFIX = "zxyz:im:lock:conversation:";
    private static final long WAIT_SECONDS = 2L;
    private static final long LEASE_SECONDS = 30L;

    private final RedissonClient redissonClient;

    public ConversationLockManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public <T> T withLock(String key, Supplier<T> supplier) {
        String lockKey = LOCK_PREFIX + key;
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(WAIT_SECONDS, LEASE_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "操作过于频繁，请稍后重试");
            }
            return supplier.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作被中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
