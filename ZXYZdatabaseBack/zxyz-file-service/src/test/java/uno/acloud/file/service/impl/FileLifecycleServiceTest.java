package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileLifecycleServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileDomainValidator fileDomainValidator;

    @Mock
    private ShareCleanupClient shareCleanupClient;

    @Mock
    private FileAccessGuard fileAccessGuardService;

    @Mock
    private FileObjectReferenceManager fileObjectReferenceService;

    @Mock
    private FileConverter fileConverter;

    @Mock
    private FileResourceChangedPublisher fileResourceChangedPublisher;

    @Mock
    private TransactionHelper transactionHelper;

    @Mock
    private UsageLedgerMapper usageLedgerMapper;

    @Mock
    private StorageCacheService storageCacheService;

    private FileLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new FileLifecycleService(
                fileMapper, fileDomainValidator, shareCleanupClient,
                fileAccessGuardService, fileObjectReferenceService, fileConverter,
                Optional.ofNullable(fileResourceChangedPublisher), transactionHelper, usageLedgerMapper,
                storageCacheService);
        // Mock TransactionHelper to execute lambdas directly
        lenient().when(transactionHelper.execute(any())).thenAnswer(invocation -> {
            TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    // ---- helpers ----

    private FileItem fileNode(Long id, int deleted) {
        FileItem node = FileItem.create();
        node.setId(id);
        node.setDeleted(deleted);
        node.setUploadUserId(100L);
        node.setTeamId(null);
        node.setSpaceType(FileSpaceType.PERSONAL);
        node.setOriginalName("test.txt");
        node.setStorePath("/test.txt");
        return node;
    }

    // ---- logicalDelete tests ----

    @Test
    void logicalDelete_throwsWhenFileAlreadyDeleted() {
        FileItem node = fileNode(1L, FileDeleteStatus.DELETED);
        List<Long> fileIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.logicalDelete(fileIds, 100L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("彻底删除"));
    }

    @Test
    void logicalDelete_throwsWhenDescendantCollectionFails() {
        FileItem node = fileNode(1L, FileDeleteStatus.NORMAL);
        List<Long> fileIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.logicalDelete(fileIds, 100L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("收集文件树节点失败"));
    }

    @Test
    void logicalDelete_callsShareCleanupAfterTransaction() {
        FileItem node = fileNode(1L, FileDeleteStatus.NORMAL);
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileConverter.toFileInfoDTO(node)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.NORMAL, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.logicalDeleteByIds(allIds, 100L)).thenReturn(1);
        when(fileMapper.getFileNodesByIds(fileIds)).thenReturn(List.of());

        service.logicalDelete(fileIds, 100L);

        verify(shareCleanupClient).deleteShareItemsByFileIds(allIds);
    }

    // ============ 批次 3（ISSUE/16）：提交后远程写失败不得把已落库的删除报成失败 ============

    @Test
    void logicalDelete_shouldStillSucceedWhenShareCleanupFails() {
        // share 侧清理失败若冒泡，前端会看到 500 而文件其实已删；重试只会撞「文件已被彻底删除」。
        // 正确口径是：以本地事务结果为准，远程残留靠对账收敛，日志留下规模与业务主键。
        FileItem node = fileNode(1L, FileDeleteStatus.NORMAL);
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileConverter.toFileInfoDTO(node)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.NORMAL, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.logicalDeleteByIds(allIds, 100L)).thenReturn(1);
        when(fileMapper.getFileNodesByIds(fileIds)).thenReturn(List.of());
        doThrow(new RuntimeException("share-service down")).when(shareCleanupClient)
                .deleteShareItemsByFileIds(allIds);

        int rows = assertDoesNotThrow(() -> service.logicalDelete(fileIds, 100L));

        assertEquals(1, rows, "分享条目清理失败不得改变「文件已逻辑删除」这个已经落库的结果");
    }

    // ---- reallyDelete tests ----

    @Test
    void reallyDelete_throwsWhenFileAlreadyDeleted() {
        FileItem node = fileNode(1L, FileDeleteStatus.DELETED);
        List<Long> fileIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reallyDelete(fileIds, 100L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("彻底删除"));
    }

    @Test
    void reallyDelete_releasesOssReferences() {
        FileItem node = fileNode(1L, FileDeleteStatus.RECYCLE);
        node.setUuidName("uuid-abc");
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);
        List<String> ossKeys = List.of("uuid-abc");

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileConverter.toFileInfoDTO(node)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.RECYCLE, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(ossKeys);
        when(fileMapper.reallyDeleteByIds(allIds, 100L)).thenReturn(1);

        service.reallyDelete(fileIds, 100L);

        verify(fileObjectReferenceService).releaseReferences(ossKeys);
        verify(shareCleanupClient).deleteShareItemsByFileIds(allIds);
    }

    @Test
    void reallyDelete_shouldStillSucceedWhenShareCleanupFails() {
        // 物理删除不可逆：这里若让 share 清理失败冒泡，用户拿到 500 却无法用「重试」修正，
        // 而 share 侧还会留下永久指向不存在文件的条目 —— 两者都比「只告警」更糟。
        FileItem node = fileNode(1L, FileDeleteStatus.RECYCLE);
        node.setUuidName("uuid-abc");
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);
        List<String> ossKeys = List.of("uuid-abc");

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileConverter.toFileInfoDTO(node)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.RECYCLE, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(ossKeys);
        when(fileMapper.reallyDeleteByIds(allIds, 100L)).thenReturn(1);
        doThrow(new RuntimeException("share-service down")).when(shareCleanupClient)
                .deleteShareItemsByFileIds(allIds);

        int rows = assertDoesNotThrow(() -> service.reallyDelete(fileIds, 100L));

        assertEquals(1, rows, "分享条目清理失败不得把已完成的物理删除对外报成失败");
    }

    @Test
    void reallyDelete_shouldInvalidateStorageUsageCache() {
        FileItem node = fileNode(1L, FileDeleteStatus.RECYCLE);
        node.setUuidName("uuid-abc");
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);
        List<String> ossKeys = List.of("uuid-abc");

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));
        when(fileConverter.toFileInfoDTO(node)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.RECYCLE, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(ossKeys);
        when(fileMapper.reallyDeleteByIds(allIds, 100L)).thenReturn(1);

        service.reallyDelete(fileIds, 100L);

        // 只有「彻底删除」才真正释放配额（回收站条目仍计入 SUM(file_size)，故逻辑删除不失效是正确的），
        // 因此彻底删除后必须失效用量缓存，否则前端容量条会停在旧值。
        verify(storageCacheService).invalidateAllStorageCaches();
        verify(fileResourceChangedPublisher).publishFromSnapshots(eq("DELETED"), anyList());
    }

    // ---- restoreFiles tests ----

    @Test
    void restoreFiles_throwsWhenFileNotInRecycle() {
        FileItem node = fileNode(1L, FileDeleteStatus.NORMAL);
        List<Long> fileIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreFiles(fileIds, 100L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不在回收站中"));
    }

    @Test
    void restoreFiles_throwsWhenFileIsDeleted() {
        FileItem node = fileNode(1L, FileDeleteStatus.DELETED);
        List<Long> fileIds = List.of(1L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(node));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.restoreFiles(fileIds, 100L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("彻底删除"));
    }
}
