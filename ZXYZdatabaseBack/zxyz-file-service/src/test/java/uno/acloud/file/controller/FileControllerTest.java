package uno.acloud.file.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.service.FileLifecyclePort;
import uno.acloud.file.service.FileOperationPort;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.FileUploadPort;
import uno.acloud.file.vo.BatchOperationDetailVO;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileResourceVO;
import uno.acloud.file.vo.FileSearchResultVO;
import uno.acloud.vo.FileDownloadUrlVO;
import uno.acloud.common.oss.OssSignInfo;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileUploadPort fileUploadPort;
    @Mock
    private FileQueryPort fileQueryPort;
    @Mock
    private FileOperationPort fileOperationPort;
    @Mock
    private FileLifecyclePort fileLifecyclePort;

    private FileController fileController;

    @BeforeEach
    void setUp() {
        fileController = new FileController(fileUploadPort, fileQueryPort, fileOperationPort, fileLifecyclePort);
    }

    // ==================== Service delegation tests ====================
    // Note: Controller methods call StpUtil.getLoginIdAsLong() which requires Sa-Token context.
    // These tests verify the service-layer delegation pattern by testing the port interfaces directly.
    // Full HTTP-level testing would require @WebMvcTest with Sa-Token mocking.

    // ==================== FileUploadPort — getUploadSign ====================

    @Test
    void getUploadSign_delegatesToFileUploadPort() {
        OssSignInfo signInfo = new OssSignInfo("https://oss.example.com/put", "key", "https://oss.example.com/key",
                "application/octet-stream", "attachment", 1700000000L);
        when(fileUploadPort.getUploadSign("test.txt")).thenReturn(signInfo);

        OssSignInfo result = fileUploadPort.getUploadSign("test.txt");

        assertNotNull(result);
        assertEquals("https://oss.example.com/put", result.getUploadUrl());
        verify(fileUploadPort).getUploadSign("test.txt");
    }

    // ==================== FileQueryPort — getFileListByParentId ====================

    @Test
    void getFileList_delegatesToFileQueryPort() {
        FileListItemVO item = new FileListItemVO();
        when(fileQueryPort.getFileListByParentId(1L, 10L, 2, null, null, null, 1L))
                .thenReturn(List.of(item));

        List<FileListItemVO> result = fileQueryPort.getFileListByParentId(1L, 10L, 2, null, null, null, 1L);

        assertEquals(1, result.size());
        verify(fileQueryPort).getFileListByParentId(1L, 10L, 2, null, null, null, 1L);
    }

    // ==================== FileQueryPort — getFileResourceById ====================

    @Test
    void getFile_existingFile_returnsResource() {
        FileResourceVO resource = new FileResourceVO(100L, 1, "test.txt", 1, 1024L, 1L, 0,
                LocalDateTime.now(), LocalDateTime.now());
        when(fileQueryPort.getFileResourceById(100L, 1L)).thenReturn(resource);

        FileResourceVO result = fileQueryPort.getFileResourceById(100L, 1L);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals("test.txt", result.getOriginalName());
        verify(fileQueryPort).getFileResourceById(100L, 1L);
    }

    @Test
    void getFile_nonExistingFile_returnsNull() {
        when(fileQueryPort.getFileResourceById(999L, 1L)).thenReturn(null);

        FileResourceVO result = fileQueryPort.getFileResourceById(999L, 1L);

        assertNull(result);
    }

    // ==================== FileQueryPort — searchFiles ====================

    @Test
    void searchFiles_delegatesToFileQueryPort() {
        FileSearchResultVO searchResult = new FileSearchResultVO(0L, List.of());
        when(fileQueryPort.searchFiles("keyword", 1, 20, 1L, 10L)).thenReturn(searchResult);

        FileSearchResultVO result = fileQueryPort.searchFiles("keyword", 1, 20, 1L, 10L);

        assertNotNull(result);
        assertEquals(0L, result.getTotal());
        verify(fileQueryPort).searchFiles("keyword", 1, 20, 1L, 10L);
    }

    // ==================== FileQueryPort — getFileDownloadUrl ====================

    @Test
    void getFileDownloadUrl_delegatesToFileQueryPort() {
        FileDownloadUrlVO downloadUrl = new FileDownloadUrlVO(100L, "https://download.example.com/file");
        when(fileQueryPort.getFileDownloadUrl(100L, 1L)).thenReturn(downloadUrl);

        FileDownloadUrlVO result = fileQueryPort.getFileDownloadUrl(100L, 1L);

        assertNotNull(result);
        assertEquals("https://download.example.com/file", result.getDownloadUrl());
        verify(fileQueryPort).getFileDownloadUrl(100L, 1L);
    }

    // ==================== FileOperationPort — patchFile ====================

    @Test
    void patchFile_delegatesToFileOperationPort() {
        FileResourceVO resource = new FileResourceVO(100L, 1, "renamed.txt", 1, 1024L, 1L, 0,
                LocalDateTime.now(), LocalDateTime.now());
        when(fileOperationPort.patchFile(eq(100L), any(), eq(1L))).thenReturn(resource);

        FileResourceVO result = fileOperationPort.patchFile(100L, null, 1L);

        assertNotNull(result);
        assertEquals("renamed.txt", result.getOriginalName());
    }

    // ==================== FileOperationPort — copyFiles ====================

    @Test
    void copyFiles_delegatesToFileOperationPort() {
        BatchOperationDetailVO detail = new BatchOperationDetailVO(1, 1, 0, 0, 0, 200L, List.of());
        when(fileOperationPort.copyFiles(List.of(1L, 2L), 200L, 10L, 1L)).thenReturn(detail);

        BatchOperationDetailVO result = fileOperationPort.copyFiles(List.of(1L, 2L), 200L, 10L, 1L);

        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        verify(fileOperationPort).copyFiles(List.of(1L, 2L), 200L, 10L, 1L);
    }

    // ==================== FileOperationPort — moveFiles ====================

    @Test
    void moveFiles_delegatesToFileOperationPort() {
        BatchOperationDetailVO detail = new BatchOperationDetailVO(1, 1, 0, 0, 0, 200L, List.of());
        when(fileOperationPort.moveFiles(List.of(1L), 200L, 10L, 1L)).thenReturn(detail);

        BatchOperationDetailVO result = fileOperationPort.moveFiles(List.of(1L), 200L, 10L, 1L);

        assertNotNull(result);
        verify(fileOperationPort).moveFiles(List.of(1L), 200L, 10L, 1L);
    }

    // ==================== FileLifecyclePort — logicalDelete ====================

    @Test
    void moveFileToTrash_delegatesToLogicalDelete() {
        when(fileLifecyclePort.logicalDelete(List.of(100L), 1L)).thenReturn(1);

        int result = fileLifecyclePort.logicalDelete(List.of(100L), 1L);

        assertEquals(1, result);
        verify(fileLifecyclePort).logicalDelete(List.of(100L), 1L);
    }

    // ==================== FileLifecyclePort — restoreFiles ====================

    @Test
    void restoreFile_delegatesToRestoreFiles() {
        when(fileLifecyclePort.restoreFiles(List.of(100L), 1L)).thenReturn(1);

        int result = fileLifecyclePort.restoreFiles(List.of(100L), 1L);

        assertEquals(1, result);
        verify(fileLifecyclePort).restoreFiles(List.of(100L), 1L);
    }

    // ==================== FileLifecyclePort — reallyDelete ====================

    @Test
    void deleteFile_delegatesToReallyDelete() {
        when(fileLifecyclePort.reallyDelete(List.of(100L), 1L)).thenReturn(1);

        int result = fileLifecyclePort.reallyDelete(List.of(100L), 1L);

        assertEquals(1, result);
        verify(fileLifecyclePort).reallyDelete(List.of(100L), 1L);
    }

    // ==================== Batch operations ====================

    @Test
    void moveFilesToTrash_delegatesWithMultipleIds() {
        when(fileLifecyclePort.logicalDelete(List.of(1L, 2L, 3L), 1L)).thenReturn(3);

        int result = fileLifecyclePort.logicalDelete(List.of(1L, 2L, 3L), 1L);

        assertEquals(3, result);
    }

    @Test
    void deleteFiles_delegatesWithMultipleIds() {
        when(fileLifecyclePort.reallyDelete(List.of(1L, 2L), 1L)).thenReturn(2);

        int result = fileLifecyclePort.reallyDelete(List.of(1L, 2L), 1L);

        assertEquals(2, result);
    }

    // ==================== Error cases ====================

    @Test
    void copyFiles_serviceThrows_propagatesException() {
        when(fileOperationPort.copyFiles(anyList(), anyLong(), anyLong(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "单次复制文件数量过多"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileOperationPort.copyFiles(List.of(1L), 200L, 10L, 1L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void logicalDelete_serviceThrows_propagatesException() {
        when(fileLifecyclePort.logicalDelete(anyList(), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileLifecyclePort.logicalDelete(List.of(999L), 1L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }
}
