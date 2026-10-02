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
        // F12：改为按受影响 scope 精确失效 —— 该节点是个人空间（spaceType=1, teamId=null）
        // 且 uploadUserId=100 ⇒ scope = (1, null, null, 100)。
        verify(storageCacheService).invalidateStorageScope(1, null, null, 100L);
        // 同时不得退回全量失效（否则等于没收敛粒度）
        verify(storageCacheService, never()).invalidateAllStorageCaches();
        verify(fileResourceChangedPublisher).publishFromSnapshots(eq("DELETED"), anyList());
    }

    // ============ F1（P1）：cleanupOrphanFolders 不得把活跃父文件夹绕过回收站 TTL 物理删除 ============

    /**
     * F1（P1，数据丢失）端到端守卫。
     *
     * <h2>真实的误操作/攻击路径</h2>
     * <p>父文件夹 P 仍然<b>活跃</b>（{@code deleted = 0}），它下面有两个子节点：</p>
     * <ul>
     *   <li>{@code F}：本次被「彻底删除」的文件（先 {@code deleted = 1} 进入回收站，再被永久删除）；</li>
     *   <li>{@code S}：仍留在回收站里的兄弟节点（{@code deleted = 1}）。</li>
     * </ul>
     * <p>{@code reallyDelete(F)} 在同一事务末尾调用 {@code cleanupOrphanFolders} 判断 P 是否已成孤儿。
     * 修复前 {@code countActiveChildren} 的 WHERE 只有 {@code deleted = 0} ⇒ 把 S 判成「不存在」
     * ⇒ 计数 0 ⇒ P 被 {@code reallyDeleteByIds} 直接置 {@code deleted = 2}，
     * <b>完全绕过回收站 30 天 TTL</b>：用户从未删除过 P，却无感知地永久丢失它且无法恢复。
     * 之后把 S 从回收站还原，还会挂到这个已死父节点下成为孤儿。</p>
     *
     * <h2>本用例的角色（避免误读覆盖面）</h2>
     * <p>Java 侧的守卫（{@code countActiveChildren(parentId) == 0} 才回收）本身是对的 ——
     * 缺陷在 SQL 的删除口径。因此这里把<b>修复后的 SQL 口径</b>（回收站子节点仍占用 ⇒ 返回 1）
     * 作为 {@code fileMapper.countActiveChildren} 的桩，钉住「满足该口径时 P 必须被保留」这一端到端契约。</p>
     * <p>而「SQL 口径本身」由无数据库可达的两道门禁钉住，它们才是本缺陷回退时会变红的用例：</p>
     * <ul>
     *   <li>{@code FileMapperWiringTest#countActiveChildrenTreatsRecycleBinChildrenAsOccupied}
     *       —— 装配期读 BoundSql 断言 {@code deleted IN (0, 1)}（无需 Docker）；</li>
     *   <li>{@code FileMapperIntegrationTest#countActiveChildrenAndGetParentId}
     *       —— 真实 MySQL 上断言软删子节点后计数不下降（需 Docker）。</li>
     * </ul>
     */
    @Test
    void reallyDelete_shouldNotPurgeActiveParentFolderWhoseRemainingChildrenAreInRecycleBin() {
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);
        List<String> ossKeys = List.of("uuid-abc");

        // 被彻底删除的文件 F：已经在回收站（deleted = 1），父目录是活跃文件夹 10
        FileItem purged = fileNode(1L, FileDeleteStatus.RECYCLE);
        purged.setUuidName("uuid-abc");
        purged.setParentId(10L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(purged));
        when(fileConverter.toFileInfoDTO(purged)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.RECYCLE, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(ossKeys);
        when(fileMapper.reallyDeleteByIds(allIds, 100L)).thenReturn(1);

        // P = 10；其兄弟节点 S 仍在回收站（deleted = 1）⇒ 修复后的口径下 P **仍有占用的子节点**
        when(fileMapper.getParentId(1L)).thenReturn(10L);
        when(fileMapper.countActiveChildren(10L)).thenReturn(1);

        service.reallyDelete(fileIds, 100L);

        verify(fileMapper, never()).reallyDeleteByIds(eq(List.of(10L)), any());
        verify(fileMapper, never()).reallyDeleteByIds(eq(List.of(10L)), eq(100L));
    }

    /**
     * F1 对照用例（保留既有正确行为）：当父文件夹 P 下确实<b>没有任何</b>
     * {@code deleted IN (0, 1)} 的子节点时，{@code cleanupOrphanFolders} 仍应把它回收，
     * 并沿祖先链继续上溯、直到遇到非空父目录或 {@code parent_id = -1} 哨兵为止。
     * <p>两个用例合起来才说明修复是「收紧口径」而不是「把清理整个关掉」。</p>
     */
    @Test
    void reallyDelete_shouldStillPurgeParentFolderWhenNoChildOccupiesIt() {
        List<Long> fileIds = List.of(1L);
        List<Long> allIds = List.of(1L);
        List<String> ossKeys = List.of("uuid-abc");

        FileItem purged = fileNode(1L, FileDeleteStatus.RECYCLE);
        purged.setUuidName("uuid-abc");
        purged.setParentId(10L);

        when(fileDomainValidator.normalizeFileIds(fileIds)).thenReturn(fileIds);
        when(fileDomainValidator.requireNodes(fileIds)).thenReturn(List.of(purged));
        when(fileConverter.toFileInfoDTO(purged)).thenReturn(
                new uno.acloud.dto.FileInfoDTO(1L, 1, "uuid-abc", "test.txt", null, null,
                        "/test.txt", null, null, FileDeleteStatus.RECYCLE, null, null));
        when(fileMapper.collectDescendantIds(fileIds)).thenReturn(allIds);
        when(fileMapper.getOssKeysByIds(allIds)).thenReturn(ossKeys);
        when(fileMapper.reallyDeleteByIds(allIds, 100L)).thenReturn(1);

        // P = 10 已无占用子节点 ⇒ 应被回收；再上溯到 P 的父 = 20，20 仍有占用子节点 ⇒ 停止
        when(fileMapper.getParentId(1L)).thenReturn(10L);
        when(fileMapper.countActiveChildren(10L)).thenReturn(0);
        when(fileMapper.getParentId(10L)).thenReturn(20L);
        when(fileMapper.countActiveChildren(20L)).thenReturn(1);

        service.reallyDelete(fileIds, 100L);

        verify(fileMapper).reallyDeleteByIds(List.of(10L), 100L);
        verify(fileMapper, never()).reallyDeleteByIds(eq(List.of(20L)), any());
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
