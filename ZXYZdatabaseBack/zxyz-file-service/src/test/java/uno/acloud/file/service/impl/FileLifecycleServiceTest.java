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

    private FileLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new FileLifecycleService(
                fileMapper, fileDomainValidator, shareCleanupClient,
                fileAccessGuardService, fileObjectReferenceService, fileConverter,
                Optional.ofNullable(fileResourceChangedPublisher), transactionHelper);
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
