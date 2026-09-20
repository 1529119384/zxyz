package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.PageResult;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.FileListItemVO;
import uno.acloud.file.vo.FileSearchItemVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
}
