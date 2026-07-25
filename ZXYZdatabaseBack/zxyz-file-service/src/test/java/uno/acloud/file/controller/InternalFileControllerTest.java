package uno.acloud.file.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalFileControllerTest {

    @Mock
    private FileQueryPort fileQueryPort;

    @Mock
    private StorageProviderRegistry registry;

    @Test
    void streamFile_existingFile_nonPresignedProvider_writesStream() throws Exception {
        FileItem fileItem = new FileItem();
        fileItem.setId(1L);
        fileItem.setOriginalName("test.txt");
        fileItem.setUuidName("files/uuid-test.txt");
        fileItem.setFileType(0);

        when(fileQueryPort.getFileNodeById(1L)).thenReturn(fileItem);

        StorageProvider provider = mock(StorageProvider.class);
        when(provider.supportsPresignedDownload()).thenReturn(false);
        when(registry.resolveForFile(fileItem)).thenReturn(provider);

        MockHttpServletResponse response = new MockHttpServletResponse();

        InternalFileController controller = new InternalFileController(fileQueryPort, registry);
        controller.streamFile(1L, response);

        assertEquals("application/octet-stream", response.getContentType());
        assertTrue(response.getHeader("Content-Disposition").contains("test.txt"));
        verify(provider).streamDownload(eq("files/uuid-test.txt"), any(OutputStream.class));
    }

    @Test
    void streamFile_presignedProvider_throwsBadRequest() {
        FileItem fileItem = new FileItem();
        fileItem.setId(1L);
        fileItem.setOriginalName("test.txt");
        fileItem.setUuidName("files/uuid-test.txt");
        fileItem.setFileType(0);

        when(fileQueryPort.getFileNodeById(1L)).thenReturn(fileItem);

        StorageProvider provider = mock(StorageProvider.class);
        when(provider.supportsPresignedDownload()).thenReturn(true);
        when(registry.resolveForFile(fileItem)).thenReturn(provider);

        InternalFileController controller = new InternalFileController(fileQueryPort, registry);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.streamFile(1L, new MockHttpServletResponse()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不支持流式下载"));
    }

    @Test
    void streamFile_notFound_throwsNotFound() {
        when(fileQueryPort.getFileNodeById(999L)).thenReturn(null);

        InternalFileController controller = new InternalFileController(fileQueryPort, registry);

        assertThrows(BusinessException.class,
                () -> controller.streamFile(999L, new MockHttpServletResponse()));
    }
}
