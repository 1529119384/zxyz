package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.PageResult;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileSearchItemVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 回收站列表的分页逻辑（07-P0-2）。
 *
 * <p>这里是分页真正落地的地方：控制器只透传参数，mapper 只是执行 SQL，
 * 归一化与 offset 计算都在本类里 —— 所以这几个用例的重点是
 * **"下发给 mapper 的 limit/offset 是否由归一化后的 page/pageSize 推出"**，
 * 而不是"有没有返回一个 list"。
 *
 * <p>刻意都用个人空间（teamId/spaceType/projectId 全 null）作为主场景：
 * 该分支下 {@code requireReadAccess} 直接返回，无需为权限守卫造桩，
 * 用例失败时就只会指向分页逻辑本身。
 *
 * <p>注意两点实体事实：{@code FileNode} 是抽象类（用 {@code FileItem} 实例化），
 * {@code FileMapper.countFileNodesInRecycleBin} 返回 {@code int} 而非 {@code long}。
 */
@ExtendWith(MockitoExtension.class)
class FileQueryServiceTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private StorageProviderRegistry registry;
    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FileConverter fileConverter;
    @Mock
    private FileAccessGuard fileAccessGuardService;

    private FileQueryService fileQueryService;

    @BeforeEach
    void setUp() {
        fileQueryService = new FileQueryService(
                fileMapper, registry, fileDomainValidator, fileConverter, fileAccessGuardService);
    }

    private static FileNode nodeWithStoredName(String storedName) {
        FileNode node = new FileItem();
        node.setOriginalName(storedName);
        return node;
    }

    // ---- getRecycleList：分页 ----

    @Test
    void getRecycleList_emptyRecycleBinSkipsPagedQuery() {
        when(fileMapper.countFileNodesInRecycleBin(null, null, null, 1L)).thenReturn(0);

        PageResult<FileListItemVO> result =
                fileQueryService.getRecycleList(null, null, null, 1L, null, null);

        assertEquals(1, result.getPage().intValue());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, result.getPageSize().intValue());
        assertEquals(0L, result.getTotal().longValue());
        assertEquals(List.of(), result.getList());
        // total 为 0 时不应再发一次注定为空的查询。
        verify(fileMapper, never())
                .getFileNodesInRecycleBinPaged(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void getRecycleListDerivesOffsetFromPageAndPageSize() {
        FileNode node = nodeWithStoredName(".deleted.999.文档.txt");
        when(fileMapper.countFileNodesInRecycleBin(null, null, null, 1L)).thenReturn(25);
        // limit=10、offset=(3-1)*10=20：参数只要有一个对不上，这个桩就不会命中。
        when(fileMapper.getFileNodesInRecycleBinPaged(null, null, null, 1L, 10, 20))
                .thenReturn(List.of(node));
        FileListItemVO vo = new FileListItemVO();
        when(fileConverter.toFileListItemVO(node)).thenReturn(vo);

        PageResult<FileListItemVO> result =
                fileQueryService.getRecycleList(null, null, null, 1L, 3, 10);

        assertEquals(3, result.getPage().intValue());
        assertEquals(10, result.getPageSize().intValue());
        assertEquals(25L, result.getTotal().longValue());
        assertEquals(List.of(vo), result.getList());
        // 墓碑前缀应被剥掉（就地改写实体），这是原来就有的行为，分页改造不应丢掉。
        assertEquals("文档.txt", node.getOriginalName());
    }

    @Test
    void getRecycleListClampsOversizedPageSizeBeforeQuerying() {
        when(fileMapper.countFileNodesInRecycleBin(null, null, null, 1L)).thenReturn(1);
        when(fileMapper.getFileNodesInRecycleBinPaged(null, null, null, 1L, PageResult.MAX_PAGE_SIZE, 0))
                .thenReturn(List.of());

        PageResult<FileListItemVO> result =
                fileQueryService.getRecycleList(null, null, null, 1L, 1, 5000);

        assertEquals(PageResult.MAX_PAGE_SIZE, result.getPageSize().intValue());
        // 关键：被钳制后的值必须真的下发给查询，否则上限只是"回给前端好看"。
        verify(fileMapper).getFileNodesInRecycleBinPaged(null, null, null, 1L, PageResult.MAX_PAGE_SIZE, 0);
    }

    @Test
    void getRecycleListNonPositivePageAndSizeFallBackToDefaults() {
        when(fileMapper.countFileNodesInRecycleBin(null, null, null, 1L)).thenReturn(3);
        when(fileMapper.getFileNodesInRecycleBinPaged(null, null, null, 1L, PageResult.DEFAULT_PAGE_SIZE, 0))
                .thenReturn(List.of());

        PageResult<FileListItemVO> result =
                fileQueryService.getRecycleList(null, null, null, 1L, 0, -1);

        assertEquals(1, result.getPage().intValue());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, result.getPageSize().intValue());
        verify(fileMapper).getFileNodesInRecycleBinPaged(null, null, null, 1L, PageResult.DEFAULT_PAGE_SIZE, 0);
    }

    // ---- getRecycleList：空间分支与权限 ----

    @Test
    void getRecycleListTeamSpaceRequiresTeamViewPermission() {
        when(fileMapper.countFileNodesInRecycleBin(7L, null, null, 1L)).thenReturn(0);

        fileQueryService.getRecycleList(7L, null, null, 1L, null, null);

        verify(fileAccessGuardService).requireTeamViewPermission(7L, 1L);
        verify(fileMapper).countFileNodesInRecycleBin(7L, null, null, 1L);
    }

    @Test
    void getRecycleListProjectSpaceForwardsSpaceTypeAndProjectId() {
        when(fileMapper.countFileNodesInRecycleBin(7L, 3, 9L, 1L)).thenReturn(0);

        PageResult<FileListItemVO> result =
                fileQueryService.getRecycleList(7L, 3, 9L, 1L, 1, 20);

        verify(fileAccessGuardService).requireProjectFileAccess(9L, 1L);
        assertEquals(0L, result.getTotal().longValue());
        assertEquals(List.of(), result.getList());
    }

    // ---- getFileListByParentId / searchFiles：默认页长与上限统一（07-P2-4） ----

    /**
     * 个人空间的目录节点。
     *
     * <p>{@code spaceType} 传 null 也没关系 —— {@code SpaceTarget.fromNode} 会走
     * {@code FileSpaceType.normalize(null, null, null)} 落成 <b>PERSONAL(1)</b>，
     * 所以下游 {@code countByParentId} / {@code getFileNodesByParentIdPaged} 收到的
     * {@code spaceType} 是 {@code 1} 而不是 {@code null}（桩里必须写 1，否则 strict stubbing 直接报错）。</p>
     */
    private static FileNode personalFolder() {
        FileNode node = new FileItem();
        node.setTeamId(null);
        node.setProjectId(null);
        node.setSpaceType(null);
        return node;
    }

    /**
     * 目录列表的默认页长是 50（历史口径），<b>不是</b> {@link PageResult#DEFAULT_PAGE_SIZE}(20)：
     * 两者语义不同，不能互相顶替。
     */
    @Test
    void getFileListByParentIdKeepsFileListingDefaultNotGlobalDefault() {
        when(fileDomainValidator.requireNode(1L, 1L, fileAccessGuardService)).thenReturn(personalFolder());
        when(fileMapper.countByParentId(1L, null, FileSpaceType.PERSONAL, null, 1L)).thenReturn(0);

        PageResult<FileListItemVO> result = fileQueryService.getFileListByParentId(
                1L, null, null, null, null, null, null, null, 1L);

        assertEquals(1, result.getPage().intValue());
        assertEquals(50, result.getPageSize().intValue());
        assertNotEquals(PageResult.DEFAULT_PAGE_SIZE, result.getPageSize().intValue());
        // total 为 0 时不应再发一次注定为空的查询。
        verify(fileMapper, never()).getFileNodesByParentIdPaged(
                any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    /**
     * 页长上限必须与 {@link PageResult#MAX_PAGE_SIZE} 一致。
     *
     * <p>旧实现硬编码 {@code Math.min(pageSize, 100)}，而前端 el-pagination 的选项上界是 200
     * ⇒ 用户选 200 时后端只按 100 分页、前端却按 200 算总页数，<b>每翻一页跳过 100 条</b>。
     * 这条用例把「上限 == MAX_PAGE_SIZE」钉死，改回去就会红。</p>
     */
    @Test
    void getFileListByParentIdClampsPageSizeToGlobalMaxBeforeQuerying() {
        when(fileDomainValidator.requireNode(1L, 1L, fileAccessGuardService)).thenReturn(personalFolder());
        when(fileMapper.countByParentId(1L, null, FileSpaceType.PERSONAL, null, 1L)).thenReturn(1);
        when(fileMapper.getFileNodesByParentIdPaged(
                        1L, null, FileSpaceType.PERSONAL, null, 1L, PageResult.MAX_PAGE_SIZE, 0))
                .thenReturn(List.of());

        PageResult<FileListItemVO> result = fileQueryService.getFileListByParentId(
                1L, null, null, null, null, null, 1, 5000, 1L);

        assertEquals(PageResult.MAX_PAGE_SIZE, result.getPageSize().intValue());
        // 关键：钳制后的值必须真的下发给查询，否则上限只是"回给前端好看"。
        verify(fileMapper).getFileNodesByParentIdPaged(
                1L, null, FileSpaceType.PERSONAL, null, 1L, PageResult.MAX_PAGE_SIZE, 0);
    }

    /**
     * 搜索旧实现返回的 {@code FileSearchResultVO} 只有 {total, list}，<b>不含 page / pageSize</b>，
     * 前端因此无法从响应里校准分页器。换成本信封后两个字段是纯新增。
     */
    @Test
    void searchFilesReturnsFullEnvelopeAndClampsPageSize() {
        when(fileMapper.countByKeyword(1L, null, "报表")).thenReturn(3);
        when(fileMapper.searchByKeyword(1L, null, "报表", PageResult.MAX_PAGE_SIZE,
                PageResult.offsetOf(2, PageResult.MAX_PAGE_SIZE))).thenReturn(List.of());

        PageResult<FileSearchItemVO> result = fileQueryService.searchFiles("报表", 2, 5000, 1L, null);

        assertEquals(2, result.getPage().intValue());
        assertEquals(PageResult.MAX_PAGE_SIZE, result.getPageSize().intValue());
        assertEquals(3L, result.getTotal().longValue());
        assertEquals(List.of(), result.getList());
    }

    /** 搜索默认页长是 20；total 为 0 时不发查询。 */
    @Test
    void searchFilesFallsBackToSearchDefaultPageSize() {
        when(fileMapper.countByKeyword(1L, null, "报表")).thenReturn(0);

        PageResult<FileSearchItemVO> result = fileQueryService.searchFiles("报表", null, null, 1L, null);

        assertEquals(1, result.getPage().intValue());
        assertEquals(20, result.getPageSize().intValue());
        assertEquals(0L, result.getTotal().longValue());
        verify(fileMapper, never()).searchByKeyword(anyLong(), any(), any(), anyInt(), anyInt());
    }

    // ---- getFileNodeForStream：F3（P2）已删文件不得再流式下载 ----

    private static FileItem streamableFile(int deleted) {
        FileItem item = new FileItem();
        item.setId(1L);
        item.setFileType(1);
        item.setOriginalName("test.txt");
        item.setUuidName("files/uuid-test.txt");
        item.setDeleted(deleted);
        return item;
    }

    /**
     * F3（P2）定点回归网：{@code getFileNodeForStream} 必须拒绝回收站（{@code deleted = 1}）
     * 与已彻底删除（{@code deleted = 2}）的节点。
     *
     * <h2>真实的绕过路径</h2>
     * <p>本方法服务 {@code GET /api/files/{id}/stream} —— 前端 {@code useFileDownload.js:43}
     * 与 {@code backendArchive.js:70} 在 {@code directDownload === false}（本地存储等非预签名提供者）
     * 时会直接请求它。修复前本方法只做 {@code requireNode(fileId, userId, guard)}，
     * 而 {@code requireNode} 查的 {@code FileMapper.getFileNodeById} <b>不带</b> {@code deleted} 谓词
     * ⇒ 「已彻底删除」的文件在 OSS 物理删除发生前的窗口内仍可被完整下载，
     * 用户可见的「不可逆删除」承诺被绕过。</p>
     * <p>对照组：{@code getFileDownloadUrl} 与 {@code getSharedFileDownloadUrl} 早就强制
     * {@code deleted = 0}（{@code FileQueryService.java:118} / {@code :144}）—— 本方法是唯一漏网读路径。</p>
     */
    @Test
    void getFileNodeForStreamRejectsRecycleBinAndHardDeletedNodes() {
        for (int deleted : new int[]{FileDeleteStatus.RECYCLE, FileDeleteStatus.DELETED}) {
            FileItem recycledOrGone = streamableFile(deleted);
            when(fileDomainValidator.requireNode(1L, 1L, fileAccessGuardService)).thenReturn(recycledOrGone);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> fileQueryService.getFileNodeForStream(1L, 1L),
                    "deleted=" + deleted + " 的文件不得经 /stream 下载（F3，P2）");
            assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode(),
                    "deleted=" + deleted + " 应被状态守卫拒绝");
        }
    }

    /**
     * 对照用例：活跃文件（{@code deleted = 0}）必须照常放行，
     * 说明修复是「加状态守卫」而不是「把流式下载整个关掉」。
     * <p>同时钉住顺序：<b>先鉴权、后判状态</b>。若先判状态，攻击者可用
     * 「状态错误」与「权限错误」的差异来探测「该 id 是否存在且已被删除」。</p>
     */
    @Test
    void getFileNodeForStreamAllowsActiveNodeAndChecksAccessFirst() {
        FileItem active = streamableFile(FileDeleteStatus.NORMAL);
        when(fileDomainValidator.requireNode(1L, 1L, fileAccessGuardService)).thenReturn(active);

        assertSame(active, fileQueryService.getFileNodeForStream(1L, 1L),
                "活跃文件必须照常返回，否则流式下载整体失效");
        // 鉴权必须发生（requireNode 的第 3 参即 guard，由 validator 内部调用）
        verify(fileDomainValidator).requireNode(1L, 1L, fileAccessGuardService);
    }

    /**
     * F3 命名陷阱守卫：{@code FileQueryPort#getFileNodeById} 与
     * {@code FileMapper#getFileNodeById} <b>同名但语义相反</b> —— 前者只返回活跃节点。
     *
     * <p>端口方法服务内部三个窄端点（{@code /stream-info}、{@code /stream}、
     * {@code /share-download-url}），它们拿 {@code null} 即报 {@code NOT_FOUND}。
     * 若实现被「顺手统一」成 mapper 的同名方法（那一个<b>不带</b> {@code deleted} 谓词），
     * 就会静默引入「已彻底删除的文件仍可下载」这一真实缺陷。</p>
     * <p>此处断言下发给 mapper 的查询<b>必须</b>是带 {@code deleted = 0} 的
     * {@code getActiveFileNodeById}，而<b>绝不能</b>是不过滤删除状态的 {@code getFileNodeById}。</p>
     */
    @Test
    void getFileNodeByIdPortMustQueryActiveRowsOnly() {
        FileItem active = streamableFile(FileDeleteStatus.NORMAL);
        when(fileMapper.getActiveFileNodeById(1L)).thenReturn(active);

        assertSame(active, fileQueryService.getFileNodeById(1L),
                "port#getFileNodeById 必须返回活跃节点");
        verify(fileMapper).getActiveFileNodeById(1L);
        verify(fileMapper, never()).getFileNodeById(any());
    }

    // ---- F16：LIKE 通配符必须转义为字面量 ----

    /**
     * F16（P3 数据正确性）：用户输入的 {@code %} / {@code _} / {@code \} 必须转义为字面量，
     * 否则搜索会退化成通配查询 —— 搜 {@code %} 命中<b>全部</b>文件、搜 {@code _} 命中任意单字符。
     *
     * <h2>为什么在服务层转义</h2>
     * <p>{@code countByKeyword} 与 {@code searchByKeyword} 是必须同形的孪生对（不同步会出现
     * 「总条数与翻页结果对不上」）。转义放在唯一调用点，天然保证两者拿到同一个转义后的值；
     * 放 SQL 里则要写两遍 REPLACE 嵌套，容易漏改一处。</p>
     *
     * <h2>转义顺序（本用例的核心）</h2>
     * <p>必须<b>先转义反斜杠自身</b>再转义 {@code %}/{@code _}。反过来的话，输入 {@code \%}
     * 会先变成 {@code \\%}，把用户本意的「转义字符 + 百分号」错误地变成「字面反斜杠 + 通配符」。</p>
     */
    @Test
    void searchFiles_escapesLikeWildcardsBeforeQuerying() {
        // 输入 "100%" ⇒ 服务层应下发给 mapper "100\%"
        when(fileMapper.countByKeyword(1L, null, "100\\%")).thenReturn(0);

        fileQueryService.searchFiles("100%", 1, 20, 1L, null);

        verify(fileMapper).countByKeyword(1L, null, "100\\%");
    }

    @Test
    void searchFiles_escapesUnderscoreAndBackslash() {
        when(fileMapper.countByKeyword(1L, null, "a\\_b\\\\c")).thenReturn(0);

        fileQueryService.searchFiles("a_b\\c", 1, 20, 1L, null);

        // "a_b\c" ⇒ _ → \_ ，\ → \\（反斜杠先转义，故顺序为 a\_b\\c）
        verify(fileMapper).countByKeyword(1L, null, "a\\_b\\\\c");
    }

    /** 普通关键词不得被改动（转义只影响元字符）。 */
    @Test
    void searchFiles_leavesPlainKeywordUnchanged() {
        when(fileMapper.countByKeyword(1L, null, "报表")).thenReturn(0);

        fileQueryService.searchFiles("报表", 1, 20, 1L, null);

        verify(fileMapper).countByKeyword(1L, null, "报表");
    }

    /** 关键词两侧空白仍被 trim（既有行为，不得因转义而回退）。 */
    @Test
    void searchFiles_stillTrimsKeyword() {
        when(fileMapper.countByKeyword(1L, null, "报表")).thenReturn(0);

        fileQueryService.searchFiles("  报表  ", 1, 20, 1L, null);

        verify(fileMapper).countByKeyword(1L, null, "报表");
    }
}
