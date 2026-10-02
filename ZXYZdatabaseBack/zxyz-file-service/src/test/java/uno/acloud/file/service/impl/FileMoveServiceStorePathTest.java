package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.dto.RenameFileRequest;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.storage.StorageProviderRegistry;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F18（P3 数据一致性）：移动文件夹时，<b>回收站内</b>的后代也必须改写 store_path。
 *
 * <h2>修复前的缺陷</h2>
 * <p>移动文件夹时 {@code collectDescendantNodes}（CTE 的种子与递归步都限定 {@code deleted = 0}）
 * 只预载<b>活跃</b>后代，因此回收站里的后代完全不被改写，store_path 停留在旧前缀。</p>
 * <p>后果延迟显现：用户把这些子节点从回收站还原时，
 * {@code FileLifecycleService#buildRenamedStorePath} 会以<b>旧</b> store_path 重建路径
 * ⇒ 还原后的节点路径与祖先链不一致。而 {@code reduceToTopLevelNodes}、
 * {@code validateFolderTarget} 等按<b>前缀</b>判断从属关系的逻辑会因此误判
 * （典型：「不能把文件夹移动到自身子目录」的守卫失效）。</p>
 *
 * <h2>修复方式与顺序</h2>
 * <p>复用已有的 {@code renameDescendantStorePaths}（其 WHERE 为
 * {@code store_path LIKE CONCAT(oldPrefix,'/%') AND deleted IN (0,1)}，恰好覆盖活跃 + 回收站），
 * 且在<b>活跃后代遍历之后</b>调用 —— 此时活跃节点已改成新前缀、不再匹配 {@code oldPrefix/%}，
 * 于是这条前缀 UPDATE 实际只命中「仍停留在旧前缀的回收站后代」，不会重复改写。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileMoveServiceStorePathTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FilePathResolver filePathResolver;
    @Mock
    private FileAccessGuard fileAccessGuardService;
    @Mock
    private FileOperationHelper helper;
    @Mock
    private uno.acloud.common.util.TransactionHelper transactionHelper;
    @Mock
    private StorageProviderRegistry registry;

    private FileMoveService service;

    @BeforeEach
    void setUp() {
        service = new FileMoveService(fileMapper, fileDomainValidator, filePathResolver,
                fileAccessGuardService, helper, transactionHelper, 500);
        lenient().when(transactionHelper.execute(any())).thenAnswer(invocation -> {
            uno.acloud.common.util.TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private Folder folder(long id, String name, String storePath, Long parentId) {
        Folder f = Folder.create();
        f.setId(id);
        f.setFileType(0);
        f.setOriginalName(name);
        f.setStorePath(storePath);
        f.setParentId(parentId);
        f.setUploadUserId(1L);
        f.setTeamId(null);
        f.setSpaceType(FileSpaceType.PERSONAL);
        return f;
    }

    /**
     * 移动一个文件夹：必须在活跃后代遍历<b>之后</b>，用旧前缀调一次
     * {@code renameDescendantStorePaths}（F18）。
     */
    @Test
    void moveFolder_rewritesRecycledDescendantStorePathsWithOldPrefix() {
        Folder moved = folder(50L, "src", "/src", -1L);
        Folder target = folder(200L, "dst", "/dst", -1L);
        target.setId(200L);

        when(fileDomainValidator.requireMovableNodes(List.of(50L))).thenReturn(List.of(moved));
        when(fileDomainValidator.requireTargetFolder(200L)).thenReturn(target);
        when(helper.resolveOperationTarget(any(), any(), any(), any(), any()))
                .thenReturn(new SpaceTarget(null, FileSpaceType.PERSONAL, null));
        when(helper.resolveMoveName(any(), any(), any())).thenReturn("src");
        when(helper.isRenamed(any(), any())).thenReturn(false);
        when(filePathResolver.buildStorePath(any(), anyString())).thenReturn("/dst/src");
        when(fileMapper.moveNodeById(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(List.of());
        when(helper.buildBatchResult(anyList(), any())).thenReturn(null);

        service.moveFiles(List.of(50L), 200L, null, 1L);

        // F18 的关键断言：以**旧** store_path 为前缀改写后代（覆盖回收站内的那些）
        verify(fileMapper).renameDescendantStorePaths("/src", "/dst/src");
    }

    /**
     * 顺序断言：前缀改写必须在「活跃后代逐行更新」<b>之后</b>发生。
     *
     * <p>顺序若反过来，活跃后代会被前缀 UPDATE 先改一遍、随后逐行遍历又按旧 childrenMap
     * 里的名字重算一次 —— 虽然结果多半相同，但一旦中间失败就会留下半新半旧的前缀，
     * 且「只命中回收站后代」这一性质不再成立。</p>
     */
    @Test
    void moveFolder_rewritesRecycledPathsAfterActiveDescendantWalk() {
        Folder moved = folder(50L, "src", "/src", -1L);
        Folder target = folder(200L, "dst", "/dst", -1L);
        Folder activeChild = folder(60L, "child", "/src/child", 50L);

        when(fileDomainValidator.requireMovableNodes(List.of(50L))).thenReturn(List.of(moved));
        when(fileDomainValidator.requireTargetFolder(200L)).thenReturn(target);
        when(helper.resolveOperationTarget(any(), any(), any(), any(), any()))
                .thenReturn(new SpaceTarget(null, FileSpaceType.PERSONAL, null));
        when(helper.resolveMoveName(any(), any(), any())).thenReturn("src");
        when(helper.isRenamed(any(), any())).thenReturn(false);
        when(filePathResolver.buildStorePath(any(), anyString())).thenReturn("/dst/src");
        when(fileMapper.moveNodeById(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(List.of(activeChild));
        when(helper.buildChildrenMap(anyList())).thenReturn(java.util.Map.of(50L, List.of(activeChild)));
        when(fileMapper.updateStorePathAndSpaceById(any(), anyString(), any(), any(), any())).thenReturn(1);
        when(helper.buildBatchResult(anyList(), any())).thenReturn(null);
        // helper 是 mock ⇒ walkDescendantsPreloaded 默认什么都不做。
        // 这里显式让它对 activeChild 调一次 action，以复刻真实遍历行为。
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            FileOperationHelper.DescendantAction<String> action = invocation.getArgument(3);
            action.apply(activeChild, "/dst/src");
            return null;
        }).when(helper).walkDescendantsPreloaded(any(), any(), any(), any());

        service.moveFiles(List.of(50L), 200L, null, 1L);

        // 先逐行更新活跃后代（含空间三列），再按前缀补齐回收站后代
        org.mockito.InOrder inOrder = inOrder(fileMapper);
        inOrder.verify(fileMapper).updateStorePathAndSpaceById(eq(60L), anyString(), any(), any(), any());
        inOrder.verify(fileMapper).renameDescendantStorePaths("/src", "/dst/src");
    }

    /**
     * 顶层节点是<b>文件</b>时不得调用前缀改写（没有后代，前缀 UPDATE 会是纯浪费；
     * 更重要的是旧前缀恰好等于该文件自身路径时不应误伤—— 该方法本身也做了 old==new 短路）。
     */
    @Test
    void moveFile_doesNotRewriteDescendantPaths() {
        uno.acloud.file.infrastructure.entity.FileItem file =
                uno.acloud.file.infrastructure.entity.FileItem.create();
        file.setId(70L);
        file.setFileType(1);
        file.setOriginalName("a.txt");
        file.setStorePath("/a.txt");
        file.setParentId(-1L);
        file.setUploadUserId(1L);
        file.setSpaceType(FileSpaceType.PERSONAL);

        when(fileDomainValidator.requireMovableNodes(List.of(70L))).thenReturn(List.of(file));
        when(fileDomainValidator.requireTargetFolder(200L)).thenReturn(null);
        when(helper.resolveOperationTarget(any(), any(), any(), any(), any()))
                .thenReturn(new SpaceTarget(null, FileSpaceType.PERSONAL, null));
        when(helper.resolveMoveName(any(), any(), any())).thenReturn("a.txt");
        when(helper.isRenamed(any(), any())).thenReturn(false);
        when(filePathResolver.buildStorePath(any(), anyString())).thenReturn("/a.txt");
        when(fileMapper.moveNodeById(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(helper.buildBatchResult(anyList(), any())).thenReturn(null);

        service.moveFiles(List.of(70L), 200L, null, 1L);

        verify(fileMapper, never()).renameDescendantStorePaths(anyString(), anyString());
        verify(fileMapper, never()).updateStorePathAndSpaceById(any(), anyString(), any(), any(), any());
    }

    /**
     * 同名移动（oldPrefix == newPrefix）时不得发起无意义的前缀 UPDATE。
     * <p>把文件夹移到同级下、名字也没变时就是这种形态。</p>
     */
    @Test
    void moveFolder_withUnchangedStorePath_skipsPrefixRewrite() {
        Folder moved = folder(50L, "src", "/dst/src", 200L);
        Folder target = folder(200L, "dst", "/dst", -1L);

        when(fileDomainValidator.requireMovableNodes(List.of(50L))).thenReturn(List.of(moved));
        when(fileDomainValidator.requireTargetFolder(200L)).thenReturn(target);
        when(helper.resolveOperationTarget(any(), any(), any(), any(), any()))
                .thenReturn(new SpaceTarget(null, FileSpaceType.PERSONAL, null));
        when(helper.resolveMoveName(any(), any(), any())).thenReturn("src");
        when(helper.isRenamed(any(), any())).thenReturn(false);
        when(filePathResolver.buildStorePath(any(), anyString())).thenReturn("/dst/src");
        when(fileMapper.moveNodeById(any(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(1);
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(List.of());
        when(helper.buildBatchResult(anyList(), any())).thenReturn(null);

        service.moveFiles(List.of(50L), 200L, null, 1L);

        verify(fileMapper, never()).renameDescendantStorePaths(anyString(), anyString());
    }
}
