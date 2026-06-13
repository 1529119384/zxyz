package uno.acloud.im.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationLockManagerTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @Test
    void shouldExecuteSupplierWhenLockAcquired() throws Exception {
        when(redissonClient.getLock("zxyz:im:lock:conversation:conversation-message:1")).thenReturn(lock);
        when(lock.tryLock(2, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        ConversationLockManager manager = new ConversationLockManager(redissonClient);
        String result = manager.withLock("conversation-message:1", () -> "ok");

        assertEquals("ok", result);
        verify(lock).unlock();
    }

    @Test
    void shouldRejectWhenLockAcquisitionFails() throws Exception {
        when(redissonClient.getLock("zxyz:im:lock:conversation:conversation-read:9")).thenReturn(lock);
        when(lock.tryLock(2, 30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(false);

        ConversationLockManager manager = new ConversationLockManager(redissonClient);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> manager.withLock("conversation-read:9", () -> "never"));

        assertEquals(ErrorCode.CONCURRENT_OPERATION, exception.getErrorCode());
        assertEquals("操作过于频繁，请稍后重试", exception.getMessage());
    }
}
