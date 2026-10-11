package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.im.FileCardEntryRequest;
import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.vo.im.FileCardSnapshotVO;
import uno.acloud.vo.FileDownloadUrlVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ImFileCardService} 的真实语义测试（P1-1 重构配套，ISSUE/54 §2.3 假证据修复）。
 *
 * <p><b>重构前的问题</b>：旧测试链 mock 掉 AuthServicePort 拿 userId ——
 * 但生产主路径（im→file 内部链路）根本没有用户会话，该 mock 提供的是<b>假证据</b>：
 * 集成路径（内部端点 → 头解析 → service）从未被真实语义覆盖，6 月起线上 401 断裂无测试捕获。</p>
 *
 * <p><b>重构后的语义</b>：userId 是 Service 的方法参数（来自 X-Acting-User-Id 头，
 * 由 Controller 解析后传入），本类按真实传参链路验证：
 * userId 被用于文件可见性过滤（getFileInfoByIds）、快照归属（ownerUserId）、
 * 下载 URL 的授权主体（getFileDownloadUrl）。</p>
 */
@ExtendWith(MockitoExtension.class)
class ImFileCardServiceTest {

    private static final Long USER_ID = 42L;

    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FileQueryPort fileQueryPort;

    private ImFileCardService service;

    @BeforeEach
    void setUp() {
        service = new ImFileCardService(fileDomainValidator, fileQueryPort);
    }

    private static FileInfoDTO file(long id, String name, long parentId) {
        FileInfoDTO dto = new FileInfoDTO();
        dto.setId(id);
        dto.setFileType(1);
        dto.setOriginalName(name);
        dto.setFileSize(10L);
        dto.setParentId(parentId);
        dto.setDeleted(0);
        dto.setUuidName("uuid-" + id);
        dto.setStorePath("/p/" + id);
        return dto;
    }

    private static FileDownloadUrlVO downloadUrl(long fileId) {
        return new FileDownloadUrlVO(fileId, "https://oss.example.com/" + fileId, true, "f" + fileId);
    }

    // ==================== createSnapshot ====================

    @Test
    void createSnapshot_passesActingUserIdIntoAccessQueryAndOwnership() {
        when(fileDomainValidator.normalizeFileIds(List.of(11L)))
                .thenReturn(List.of(11L));
        when(fileQueryPort.getFileInfoByIds(List.of(11L), USER_ID))
                .thenReturn(List.of(file(11L, "a.txt", -1L)));

        FileCardSnapshotRequest request = new FileCardSnapshotRequest();
        request.setFileIds(List.of(11L));

        FileCardSnapshotVO vo = service.createSnapshot(request, USER_ID);

        assertEquals("SINGLE_FILE", vo.getShareType());
        // ownerUserId 必须是传入的操作者 userId（不再是会话语义取值）
        assertEquals(USER_ID, vo.getOwnerUserId());
        assertEquals(1, vo.getEntryCount());
        // userId 必须真实进入可见性查询（文件访问控制主体）
        verify(fileQueryPort).getFileInfoByIds(List.of(11L), USER_ID);
    }

    @Test
    void createSnapshot_rejectsWhenAnyFileInaccessibleOrDeleted() {
        when(fileDomainValidator.normalizeFileIds(List.of(11L, 12L)))
                .thenReturn(List.of(11L, 12L));
        // 只返回 11 —— 12 已删除/不存在，必须整体拒绝（不可静默少文件）
        when(fileQueryPort.getFileInfoByIds(List.of(11L, 12L), USER_ID))
                .thenReturn(List.of(file(11L, "a.txt", -1L)));

        FileCardSnapshotRequest request = new FileCardSnapshotRequest();
        request.setFileIds(List.of(11L, 12L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSnapshot(request, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("删除"));
    }

    @Test
    void createSnapshot_rejectsMultiFileFromDifferentParents() {
        when(fileDomainValidator.normalizeFileIds(List.of(11L, 12L)))
                .thenReturn(List.of(11L, 12L));
        when(fileQueryPort.getFileInfoByIds(List.of(11L, 12L), USER_ID))
                .thenReturn(List.of(file(11L, "a.txt", 1L), file(12L, "b.txt", 2L)));

        FileCardSnapshotRequest request = new FileCardSnapshotRequest();
        request.setFileIds(List.of(11L, 12L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createSnapshot(request, USER_ID));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("同一文件夹"));
    }

    // ==================== resolve ====================

    @Test
    void resolve_usesActingUserIdForAccessQueryAndDownloadUrl() {
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(USER_ID);
        FileCardEntryRequest entry = new FileCardEntryRequest();
        entry.setFileId(11L);
        entry.setFileType(1);
        entry.setOriginalName("a.txt");
        entry.setParentId(-1L);
        entry.setStorePath("/p/11");
        entry.setModifyTime(LocalDateTime.of(2026, 4, 27, 13, 45, 12));
        request.setEntries(List.of(entry));

        when(fileQueryPort.getFileInfoByIds(List.of(11L), USER_ID))
                .thenReturn(List.of(file(11L, "a.txt", -1L)));
        when(fileQueryPort.getFileDownloadUrl(11L, USER_ID)).thenReturn(downloadUrl(11L));

        var vo = service.resolve(request, USER_ID);

        assertEquals("AVAILABLE", vo.getStatus());
        assertEquals("a.txt", vo.getTitle());
        assertEquals("https://oss.example.com/11", vo.getDownloadUrl());
        // userId 必须真实进入可见性查询与下载 URL 授权
        verify(fileQueryPort).getFileInfoByIds(List.of(11L), USER_ID);
        verify(fileQueryPort).getFileDownloadUrl(11L, USER_ID);
    }

    @Test
    void resolve_reportsDeletedWhenFileGone() {
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(USER_ID);
        FileCardEntryRequest entry = new FileCardEntryRequest();
        entry.setFileId(11L);
        entry.setFileType(1);
        entry.setOriginalName("a.txt");
        entry.setParentId(-1L);
        entry.setStorePath("/p/11");
        request.setEntries(List.of(entry));

        // 该用户视角下文件已不在（删除/无权） → DELETED
        when(fileQueryPort.getFileInfoByIds(List.of(11L), USER_ID))
                .thenReturn(List.of());

        var vo = service.resolve(request, USER_ID);

        assertEquals("DELETED", vo.getStatus());
        assertNull(vo.getDownloadUrl());
    }

    @Test
    void resolve_rejectsEmptyEntries() {
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(USER_ID);
        request.setEntries(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolve(request, USER_ID));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("entries"));
    }

    @Test
    void resolve_detectsMovedByStorePathChange() {
        FileCardResolveRequest request = new FileCardResolveRequest();
        request.setShareType("SINGLE_FILE");
        request.setOwnerUserId(USER_ID);
        FileCardEntryRequest entry = new FileCardEntryRequest();
        entry.setFileId(11L);
        entry.setFileType(1);
        entry.setOriginalName("a.txt");
        entry.setParentId(-1L);
        entry.setStorePath("/old-path");
        request.setEntries(List.of(entry));

        FileInfoDTO moved = file(11L, "a.txt", -1L);
        moved.setStorePath("/new-path");
        when(fileQueryPort.getFileInfoByIds(List.of(11L), USER_ID)).thenReturn(List.of(moved));
        when(fileQueryPort.getFileDownloadUrl(11L, USER_ID)).thenReturn(downloadUrl(11L));

        var vo = service.resolve(request, USER_ID);

        assertEquals("MOVED", vo.getStatus());
        assertEquals("https://oss.example.com/11", vo.getDownloadUrl(),
                "MOVED 仍应给出下载 URL（文件还在，只是位置变了）");
    }
}
