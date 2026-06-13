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
        when(fileObjectRefMapper.incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.retainReference("oss-key-1"));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("引用计数增加失败"));
    }

    @Test
    void retainReference_succeedsWhenIncrementReturns1() {
        when(fileObjectRefMapper.incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE))
                .thenReturn(1);

        assertDoesNotThrow(() -> manager.retainReference("oss-key-1"));
        verify(fileObjectRefMapper).incrementReference("oss-key-1", 1, FileObjectDeleteStatus.ACTIVE);
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
}
