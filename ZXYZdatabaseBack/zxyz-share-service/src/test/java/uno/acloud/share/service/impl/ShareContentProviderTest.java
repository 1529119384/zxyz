package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ShareErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.vo.ShareFilesResponseItemVO;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareContentProviderTest {

    @Mock
    private ShareFileServiceClient fileServiceClient;

    @Mock
    private ShareValidator shareValidator;

    @Mock
    private ShareInputNormalizer shareInputNormalizer;

    @Mock
    private ShareFileResolver shareFileResolver;

    @Mock
    private ShareViewMapper shareViewMapper;

    @Mock
    private ShareAccessManager shareAccessService;

    @InjectMocks
    private ShareContentProvider shareContentProvider;

    private Share createShare(Long id) {
        Share share = new Share();
        share.setId(id);
        share.setShareKey("key" + id);
        share.setStatus(ShareStatus.NORMAL);
        return share;
    }

    private FileInfoDTO createFolder(Long id, String name, Long parentId) {
        FileInfoDTO folder = new FileInfoDTO();
        folder.setId(id);
        folder.setOriginalName(name);
        folder.setFileType(0);
        folder.setDeleted(0);
        folder.setParentId(parentId);
        return folder;
    }

    private FileInfoDTO createFile(Long id, String name, Long parentId) {
        FileInfoDTO file = new FileInfoDTO();
        file.setId(id);
        file.setOriginalName(name);
        file.setFileType(1);
        file.setDeleted(0);
        file.setParentId(parentId);
        return file;
    }

    // ==================== 多级路径解析测试 ====================

    @Test
    void getShareFiles_multiLevelPath_resolvesNestedFolder() {
        Share share = createShare(1L);
        when(shareAccessService.requireAccessibleShare("key1", null)).thenReturn(share);

        FileInfoDTO rootFolder1 = createFolder(10L, "folder1", null);
        FileInfoDTO rootFolder2 = createFolder(11L, "folder2", null);
        when(shareFileResolver.getSharedRootFileInfos(eq(1L), any()))
                .thenReturn(List.of(rootFolder1, rootFolder2));

        when(shareInputNormalizer.normalizePath("folder1/folder2/folder3"))
                .thenReturn("folder1/folder2/folder3");
        when(shareInputNormalizer.splitPath("folder1/folder2/folder3"))
                .thenReturn(List.of("folder1", "folder2", "folder3"));

        FileInfoDTO nestedFolder2 = createFolder(20L, "folder2", 10L);
        when(fileServiceClient.getShareChildren(10L)).thenReturn(List.of(nestedFolder2));

        FileInfoDTO nestedFolder3 = createFolder(30L, "folder3", 20L);
        when(fileServiceClient.getShareChildren(20L)).thenReturn(List.of(nestedFolder3));

        FileInfoDTO finalFile = createFile(40L, "doc.txt", 30L);
        when(fileServiceClient.getShareChildren(30L)).thenReturn(List.of(finalFile));

        when(shareValidator.isActive(any(FileInfoDTO.class))).thenReturn(true);

        ShareFilesResponseItemVO vo = new ShareFilesResponseItemVO(40L, "doc.txt", 0, false, 0, 0, false, null, 1024L, null);
        when(shareViewMapper.toShareFilesResponseItemVO(any(), eq(true))).thenReturn(vo);
        when(shareViewMapper.shareFileComparator()).thenReturn(Comparator.comparingInt(f -> Boolean.TRUE.equals(f.getIsFolder()) ? 0 : 1));

        List<ShareFilesResponseItemVO> result = shareContentProvider.getShareFiles("key1", "folder1/folder2/folder3", null);

        assertEquals(1, result.size());
        assertEquals("doc.txt", result.get(0).getFileName());
        verify(fileServiceClient).getShareChildren(eq(10L));
        verify(fileServiceClient).getShareChildren(eq(20L));
        verify(fileServiceClient).getShareChildren(eq(30L));
    }

    @Test
    void getShareFiles_missingIntermediateFolder_throwsBadRequest() {
        Share share = createShare(1L);
        when(shareAccessService.requireAccessibleShare("key1", null)).thenReturn(share);

        FileInfoDTO rootFolder1 = createFolder(10L, "folder1", null);
        when(shareFileResolver.getSharedRootFileInfos(eq(1L), any()))
                .thenReturn(List.of(rootFolder1));

        when(shareInputNormalizer.normalizePath("folder1/missing"))
                .thenReturn("folder1/missing");
        when(shareInputNormalizer.splitPath("folder1/missing"))
                .thenReturn(List.of("folder1", "missing"));

        FileInfoDTO otherChild = createFile(99L, "other.txt", 10L);
        when(fileServiceClient.getShareChildren(10L)).thenReturn(List.of(otherChild));
        when(shareValidator.isActive(any())).thenReturn(true);

        assertThrows(BusinessException.class,
                () -> shareContentProvider.getShareFiles("key1", "folder1/missing", null));
    }

    @Test
    void getShareFiles_inactiveFolderAtAnyLevel_throwsShareStatusInvalid() {
        Share share = createShare(1L);
        when(shareAccessService.requireAccessibleShare("key1", null)).thenReturn(share);

        FileInfoDTO rootFolder1 = createFolder(10L, "folder1", null);
        when(shareFileResolver.getSharedRootFileInfos(eq(1L), any()))
                .thenReturn(List.of(rootFolder1));

        when(shareInputNormalizer.normalizePath("folder1/folder2"))
                .thenReturn("folder1/folder2");
        when(shareInputNormalizer.splitPath("folder1/folder2"))
                .thenReturn(List.of("folder1", "folder2"));

        FileInfoDTO inactiveFolder = createFolder(20L, "folder2", 10L);
        when(fileServiceClient.getShareChildren(10L)).thenReturn(List.of(inactiveFolder));
        when(shareValidator.isActive(rootFolder1)).thenReturn(true);
        when(shareValidator.isActive(inactiveFolder)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareContentProvider.getShareFiles("key1", "folder1/folder2", null));
        assertEquals(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), ex.getErrorCode());
    }

    @Test
    void getShareFiles_emptyPath_returnsRootFiles() {
        Share share = createShare(1L);
        when(shareAccessService.requireAccessibleShare("key1", null)).thenReturn(share);

        FileInfoDTO rootFile = createFile(100L, "doc.txt", null);
        when(shareFileResolver.getSharedRootFileInfos(eq(1L), any()))
                .thenReturn(List.of(rootFile));

        when(shareInputNormalizer.normalizePath("")).thenReturn("");
        when(shareValidator.isActive(any())).thenReturn(true);

        ShareFilesResponseItemVO vo = new ShareFilesResponseItemVO(100L, "doc.txt", 0, false, 0, 0, false, null, 1024L, null);
        when(shareViewMapper.toShareFilesResponseItemVO(any(), eq(true))).thenReturn(vo);
        when(shareViewMapper.shareFileComparator()).thenReturn(Comparator.comparingInt(f -> Boolean.TRUE.equals(f.getIsFolder()) ? 0 : 1));

        List<ShareFilesResponseItemVO> result = shareContentProvider.getShareFiles("key1", "", null);

        assertEquals(1, result.size());
        verify(fileServiceClient, never()).getShareChildren(anyLong());
    }
}
