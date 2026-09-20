package uno.acloud.common.lock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DistributedLockTemplate} 的语义门禁。
 *
 * <p>重点钉住两条最容易写错、且错了只在并发下偶发暴露的语义：
 * ① 只有「真的抢到」且「仍是本线程持有」才解锁（否则 unlock 会抛 IllegalMonitorStateException）；
 * ② 抢锁被中断时必须<b>恢复中断标志</b>，否则线程池复用该线程时中断会被吞掉。</p>
 */
@ExtendWith(MockitoExtension.class)
class DistributedLockTemplateTest {

    private static final String KEY = "zxyz:test:lock:1";
    private static final long WAIT = 5L;
    private static final long LEASE = 30L;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private DistributedLockTemplate newTemplate() {
        when(redissonClient.getLock(KEY)).thenReturn(lock);
        return new DistributedLockTemplate(redissonClient);
    }

    @Test
    void shouldRunSupplierAndUnlockWhenAcquired() throws Exception {
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = template.withLock(KEY, WAIT, LEASE, () -> "ok");

        assertEquals("ok", result);
        verify(lock).unlock();
    }

    @Test
    void shouldRejectWithoutRunningSupplierWhenNotAcquired() throws Exception {
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenReturn(false);
        AtomicBoolean supplierRan = new AtomicBoolean(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> template.withLock(KEY, WAIT, LEASE, () -> {
                    supplierRan.set(true);
                    return "never";
                }));

        assertEquals(ErrorCode.CONCURRENT_OPERATION, ex.getErrorCode());
        assertEquals(DistributedLockTemplate.MESSAGE_CONCURRENT, ex.getMessage());
        assertFalse(supplierRan.get(), "抢锁失败时绝不能执行临界区");
        verify(lock, never()).unlock();
    }

    @Test
    void shouldRestoreInterruptFlagAndReportInterrupted() throws Exception {
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenThrow(new InterruptedException("boom"));

        try {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> template.withLock(KEY, WAIT, LEASE, () -> "never"));
            assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
            assertEquals(DistributedLockTemplate.MESSAGE_INTERRUPTED, ex.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(),
                    "必须恢复中断标志，否则线程池复用该线程时中断被吞掉");
        } finally {
            // 清理，避免污染同一 JVM 里的后续用例
            Thread.interrupted();
        }
        verify(lock, never()).unlock();
    }

    @Test
    void shouldStillUnlockWhenSupplierThrows() throws Exception {
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> template.withLock(KEY, WAIT, LEASE, () -> {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "业务失败");
                }));

        assertEquals("业务失败", ex.getMessage());
        verify(lock).unlock();
    }

    @Test
    void shouldNotUnlockWhenLockNoLongerHeldByCurrentThread() throws Exception {
        // 租约已过期或已被他线程持有：此时 unlock 会抛 IllegalMonitorStateException，必须跳过
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        assertEquals("ok", template.withLock(KEY, WAIT, LEASE, () -> "ok"));
        verify(lock, never()).unlock();
    }

    @Test
    void withLockVoidShouldRunActionAndUnlock() throws Exception {
        DistributedLockTemplate template = newTemplate();
        when(lock.tryLock(WAIT, LEASE, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicBoolean actionRan = new AtomicBoolean(false);

        template.withLockVoid(KEY, WAIT, LEASE, () -> actionRan.set(true));

        assertTrue(actionRan.get());
        verify(lock).unlock();
    }
}
