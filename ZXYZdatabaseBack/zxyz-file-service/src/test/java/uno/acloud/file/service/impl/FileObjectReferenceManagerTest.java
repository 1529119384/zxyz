package uno.acloud.file.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileObjectDeleteStatus;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileObjectRef;
import uno.acloud.file.infrastructure.mapper.FileObjectRefMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileObjectReferenceManagerTest {

    @Mock
    private FileObjectRefMapper fileObjectRefMapper;

    @InjectMocks
    private FileObjectReferenceManager manager;

    // ---- retainReference tests ----

    @Test
    void retainReference_throwsWhenIncrementFails() {
        when(fileObjectRefMapper.incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE, "oss"))
                .thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.retainReference("oss-key-1", "oss"));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("引用计数增加失败"));
    }

    @Test
    void retainReference_succeedsWhenIncrementReturns1() {
        when(fileObjectRefMapper.incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE, "oss"))
                .thenReturn(1);

        assertDoesNotThrow(() -> manager.retainReference("oss-key-1", "oss"));
        verify(fileObjectRefMapper).incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE, "oss");
    }

    @Test
    void retainReference_throwsWhenProviderNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.retainReference("oss-key-1", null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void retainReference_silentlyReturnsWhenKeyBlank() {
        assertDoesNotThrow(() -> manager.retainReference("  "));
        verifyNoInteractions(fileObjectRefMapper);
    }

    // ---- releaseReferences tests ----

    @Test
    void releaseReferences_silentlyReturnsWhenCollectionEmpty() {
        assertDoesNotThrow(() -> manager.releaseReferences(Collections.emptyList()));
        verifyNoInteractions(fileObjectRefMapper);
    }

    @Test
    void releaseReferences_silentlyReturnsWhenCollectionNull() {
        assertDoesNotThrow(() -> manager.releaseReferences(null));
        verifyNoInteractions(fileObjectRefMapper);
    }

    @Test
    void releaseReferences_decrementsAndMarksPendingWhenUnused() {
        when(fileObjectRefMapper.decrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(1);

        manager.releaseReferences(List.of("oss-key-1"));

        verify(fileObjectRefMapper).decrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE);
        verify(fileObjectRefMapper).markPendingIfUnused(
                "oss-key-1",
                FileObjectDeleteStatus.ACTIVE,
                FileObjectDeleteStatus.PENDING_DELETE
        );
    }

    @Test
    void releaseReferences_skipsAlreadyDeletedRef() {
        when(fileObjectRefMapper.decrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(0);

        FileObjectRef ref = new FileObjectRef();
        ref.setObjectKey("oss-key-1");
        ref.setRefCount(0);
        ref.setDeleteStatus(FileObjectDeleteStatus.DELETED);

        when(fileObjectRefMapper.selectByKey("oss-key-1")).thenReturn(ref);

        assertDoesNotThrow(() -> manager.releaseReferences(List.of("oss-key-1")));
        verify(fileObjectRefMapper).decrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE);
        verify(fileObjectRefMapper).selectByKey("oss-key-1");
        verify(fileObjectRefMapper, never()).markPendingIfUnused(anyString(), anyString(), anyString());
    }

    @Test
    void releaseReferences_throwsWhenRefCountUnderflow() {
        when(fileObjectRefMapper.decrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(0);

        FileObjectRef ref = new FileObjectRef();
        ref.setObjectKey("oss-key-1");
        ref.setRefCount(0);
        ref.setDeleteStatus(FileObjectDeleteStatus.ACTIVE);

        when(fileObjectRefMapper.selectByKey("oss-key-1")).thenReturn(ref);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.releaseReferences(List.of("oss-key-1")));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("引用计数不足"));
    }

    @Test
    void releaseReferences_handlesDuplicateKeys() {
        // Same key appears twice -> should be grouped, single decrement with count=2
        List<String> keys = Arrays.asList("oss-key-1", "oss-key-1");

        when(fileObjectRefMapper.decrementReference("oss-key-1", 2, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(1);

        manager.releaseReferences(keys);

        verify(fileObjectRefMapper).decrementReference("oss-key-1", 2, FileObjectDeleteStatus.ACTIVE);
        verify(fileObjectRefMapper).markPendingIfUnused(
                "oss-key-1",
                FileObjectDeleteStatus.ACTIVE,
                FileObjectDeleteStatus.PENDING_DELETE
        );
        // Ensure decrement was called only once (deduplicated)
        verify(fileObjectRefMapper, times(1)).decrementReference(anyString(), anyInt(), anyString());
    }

    // ---- 并发测试 ----

    @Test
    void releaseReferences_concurrentReleasesDoNotCorruptState() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        // 每次 decrementReference 返回 1（表示成功释放）
        when(fileObjectRefMapper.decrementReference(eq("oss-concurrent"), eq(1), eq(FileObjectDeleteStatus.ACTIVE)))
                .thenReturn(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    manager.releaseReferences(List.of("oss-concurrent"));
                    successCount.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }

        startLatch.countDown(); // 所有线程同时开始
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // 验证 decrementReference 被调用了 threadCount 次
        verify(fileObjectRefMapper, times(threadCount))
                .decrementReference("oss-concurrent", 1, FileObjectDeleteStatus.ACTIVE);
    }

    @Test
    void releaseReferences_concurrentDuplicateKeysAreGroupedPerThread() throws InterruptedException {
        // 每个线程释放相同的 key，但各线程内部的 groupingBy 是独立的
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        when(fileObjectRefMapper.decrementReference(eq("oss-grouped"), eq(2), eq(FileObjectDeleteStatus.ACTIVE)))
                .thenReturn(1);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // 每个线程释放两个相同的 key
                    manager.releaseReferences(List.of("oss-grouped", "oss-grouped"));
                } catch (Exception ignored) {
                }
            });
        }

        startLatch.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // 每个线程内 groupingBy 会将两个相同 key 合并为一次 decrement(count=2)
        verify(fileObjectRefMapper, times(threadCount))
                .decrementReference("oss-grouped", 2, FileObjectDeleteStatus.ACTIVE);
    }
}
