package uno.acloud.file.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.controller.model.ShareFileProjectionVO;
import uno.acloud.file.dto.InternalBatchFileIdsRequest;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.impl.FileAccessGuard;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.io.OutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalFileControllerTest {

    @Mock
    private FileQueryPort fileQueryPort;

    @Mock
    private StorageProviderRegistry registry;

    @Mock
    private FileAccessGuard fileAccessGuard;

    /**
     * 「含已删除」的批量投影端点必须把回收站(1)与彻底删除(2)的节点也返回。
     * 这是 share 侧对账任务能区分「该清 / 不该清」的唯一前提 —— 若这个端点也按 deleted=0 过滤，
     * 回收站与彻底删除在调用方看来就都是「查不到」，对账会把用户的回收站文件当孤儿清掉。
     */
    @Test
    void getBatchShareProjectionWithDeleted_shouldReturnRecycleBinAndHardDeletedNodes() {
        FileItem active = new FileItem();
        active.setId(1L);
        active.setOriginalName("active.txt");
        active.setDeleted(0);

        FileItem recycled = new FileItem();
        recycled.setId(2L);
        recycled.setOriginalName("recycled.txt");
        recycled.setDeleted(1);

        FileItem gone = new FileItem();
        gone.setId(3L);
        gone.setOriginalName("gone.txt");
        gone.setDeleted(2);

        List<Long> fileIds = List.of(1L, 2L, 3L);
        when(fileQueryPort.getFileNodesByIds(fileIds)).thenReturn(List.of(active, recycled, gone));

        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);
        InternalBatchFileIdsRequest request = new InternalBatchFileIdsRequest();
        request.setFileIds(fileIds);

        List<ShareFileProjectionVO> result = controller.getBatchShareProjectionWithDeleted(request).getData();

        assertEquals(3, result.size(), "必须返回回收站与彻底删除的节点，否则对账无法区分三者");
        assertEquals(0, result.get(0).getDeleted());
        assertEquals(1, result.get(1).getDeleted());
        assertEquals(2, result.get(2).getDeleted());
        // 钉住实现：必须走「不过滤 deleted」的查询，不能是 getActiveFileNodesByIds
        verify(fileQueryPort, never()).getActiveFileNodesByIds(any());
    }

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

        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);
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

        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.streamFile(1L, new MockHttpServletResponse()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不支持流式下载"));
    }

    @Test
    void streamFile_notFound_throwsNotFound() {
        when(fileQueryPort.getFileNodeById(999L)).thenReturn(null);

        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);

        assertThrows(BusinessException.class,
                () -> controller.streamFile(999L, new MockHttpServletResponse()));
    }

    @Test
    void streamFile_folder_throwsBadRequest() {
        Folder folder = new Folder();
        folder.setId(1L);
        folder.setOriginalName("myfolder");
        when(fileQueryPort.getFileNodeById(1L)).thenReturn(folder);

        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.streamFile(1L, new MockHttpServletResponse()));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不支持文件夹下载"));
        verifyNoInteractions(registry);
    }

    // ==================== F3 复核：内部窄端点的删除状态口径 ====================

    /**
     * F3 复核结论（<b>本组端点「实测已不成立」</b>）：内部三个窄端点
     * {@code /stream-info}、{@code /stream}、{@code /share-download-url} 走的
     * {@code FileQueryPort#getFileNodeById} <b>并不</b>包含已删除行。
     *
     * <h2>为什么分报告会看错，以及为什么必须钉住</h2>
     * <p>存在一对<b>同名但语义相反</b>的方法，这是本仓最容易看错的命名陷阱：</p>
     * <ul>
     *   <li>{@code FileMapper#getFileNodeById} —— XML 里 {@code WHERE id = #{fileId}}，
     *       <b>不带</b> {@code deleted} 谓词（含 deleted=1/2）；</li>
     *   <li>{@code FileQueryPort#getFileNodeById} —— 名字一模一样，实现却是
     *       {@code fileMapper.getActiveFileNodeById(fileId)}，即 {@code WHERE id = ? AND deleted = 0}。</li>
     * </ul>
     * <p>分报告按名字推断成前者，故判「内部端点可下载已彻底删除文件」。实际按后者。</p>
     * <p>但正因为这个命名歧义，将来任何人「顺手把实现改成 mapper 的同名方法」都会
     * <b>静默</b>引入 F3 描述的那个真实缺陷 ⇒ 本用例把「port 层必须只返回活跃节点」的行为钉死：
     * 删除状态由 <b>port 实现</b>保证，控制器拿到的 {@code null} 就是「不存在或已删除」，
     * 统一走 {@code NOT_FOUND}（不区分两者，避免向调用方泄露「该 id 存在但已删除」）。</p>
     */
    @Test
    void internalNarrowEndpointsTreatRecycledAndHardDeletedNodesAsNotFound() {
        InternalFileController controller = new InternalFileController(fileQueryPort, registry, fileAccessGuard);

        // port 层已按 deleted = 0 过滤 ⇒ 回收站(1) / 彻底删除(2) 的节点到这里都是 null
        when(fileQueryPort.getFileNodeById(1L)).thenReturn(null);

        BusinessException streamInfo = assertThrows(BusinessException.class,
                () -> controller.getFileStreamInfo(1L));
        assertEquals(ErrorCode.NOT_FOUND, streamInfo.getErrorCode(),
                "/stream-info 对回收站/已彻底删除节点必须报 NOT_FOUND");

        BusinessException stream = assertThrows(BusinessException.class,
                () -> controller.streamFile(1L, new MockHttpServletResponse()));
        assertEquals(ErrorCode.NOT_FOUND, stream.getErrorCode(),
                "/stream 对回收站/已彻底删除节点必须报 NOT_FOUND");

        BusinessException shareUrl = assertThrows(BusinessException.class,
                () -> controller.getShareDownloadUrl(1L));
        assertEquals(ErrorCode.NOT_FOUND, shareUrl.getErrorCode(),
                "/share-download-url 对回收站/已彻底删除节点必须报 NOT_FOUND");

        // 三个端点都必须经 port 的活跃查询；一旦有人把它换成不过滤 deleted 的实现，
        // 本断言不会失败但行为会变 —— 故再用「绝不触碰 registry」兜住「没走到发链接那步」。
        verify(fileQueryPort, times(3)).getFileNodeById(1L);
        verifyNoInteractions(registry);
    }
}
