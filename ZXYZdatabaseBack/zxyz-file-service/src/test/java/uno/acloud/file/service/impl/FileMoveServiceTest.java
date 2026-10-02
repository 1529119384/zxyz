package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.BatchOperationDetailVO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileMoveServiceTest {

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
    private TransactionHelper transactionHelper;

    private FileMoveService fileMoveService;

    /** 与 {@code app.file.copy.max-nodes-per-tx} 默认值一致（F10 复用它作为移动上限）。 */
    private static final int MAX_NODES_PER_TX = 500;

    @BeforeEach
    void setUp() {
        fileMoveService = new FileMoveService(
                fileMapper, fileDomainValidator, filePathResolver,
                fileAccessGuardService, helper, transactionHelper, MAX_NODES_PER_TX);
        // Mock TransactionHelper to execute lambdas directly
        lenient().when(transactionHelper.execute(any())).thenAnswer(invocation -> {
            TransactionHelper.TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    // ==================== Move file within same folder — should succeed (skipped) ====================

    @Test
    void moveFiles_sameFolder_shouldSkip() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/target/test.txt");
        sourceFile.setParentId(targetParentId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(FileSpaceType.TEAM);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(FileSpaceType.TEAM);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, FileSpaceType.TEAM, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(fileDomainValidator.isSameParent(sourceFile, targetParentId)).thenReturn(true);

        // file in same folder + same space => skipped
        BatchOperationDetailVO expectedResult = new BatchOperationDetailVO(1, 0, 0, 1, 0, targetParentId, List.of());
        when(helper.buildBatchResult(anyList(), eq(targetParentId))).thenReturn(expectedResult);

        BatchOperationDetailVO result = fileMoveService.moveFiles(
                List.of(fileNodeId), targetParentId, teamId, userId);

        assertNotNull(result);
        // Verify the file was NOT actually moved (no mapper call)
        verify(fileMapper, never()).moveNodeById(anyLong(), anyString(), anyLong(), anyString(), any(), any(), any());
        verify(helper).publishByIdsAfterCommit(eq("MOVED"), anyList(), anyList());
    }

    // ==================== Move file across folders — should succeed ====================

    @Test
    void moveFiles_acrossFolders_shouldSucceed() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long oldParentId = 50L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/old/test.txt");
        sourceFile.setParentId(oldParentId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(FileSpaceType.TEAM);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(FileSpaceType.TEAM);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, FileSpaceType.TEAM, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(fileDomainValidator.isSameParent(sourceFile, targetParentId)).thenReturn(false);
        when(helper.resolveMoveName(eq(sourceFile), any(FileOperationHelper.MoveTargetContext.class), eq(userId)))
                .thenReturn("test.txt");
        when(helper.isRenamed(sourceFile, "test.txt")).thenReturn(false);
        when(filePathResolver.buildStorePath(targetParentId, "test.txt")).thenReturn("/target/test.txt");
        when(fileMapper.moveNodeById(eq(fileNodeId), eq("test.txt"), eq(targetParentId),
                eq("/target/test.txt"), eq(teamId), eq(FileSpaceType.TEAM), isNull()))
                .thenReturn(1);

        BatchOperationDetailVO expectedResult = new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of());
        when(helper.buildBatchResult(anyList(), eq(targetParentId))).thenReturn(expectedResult);

        BatchOperationDetailVO result = fileMoveService.moveFiles(
                List.of(fileNodeId), targetParentId, teamId, userId);

        assertNotNull(result);
        verify(fileMapper).moveNodeById(eq(fileNodeId), eq("test.txt"), eq(targetParentId),
                eq("/target/test.txt"), eq(teamId), eq(FileSpaceType.TEAM), isNull());
        verify(helper).publishByIdsAfterCommit(eq("MOVED"), anyList(), anyList());
    }

    // ==================== Move file to different space type — should reject ====================

    @Test
    void moveFiles_differentSpaceType_shouldReject() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;

        // Source: personal space (teamId=null, spaceType=1)
        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/personal/test.txt");
        sourceFile.setParentId(50L);
        sourceFile.setTeamId(null);
        sourceFile.setSpaceType(FileSpaceType.PERSONAL);

        // Target: team space (teamId=10, spaceType=2)
        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("teamFolder");
        targetFolder.setStorePath("/team/teamFolder");
        targetFolder.setTeamId(10L);
        targetFolder.setSpaceType(FileSpaceType.TEAM);

        SpaceTarget spaceTarget = new SpaceTarget(10L, FileSpaceType.TEAM, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, 10L, null, null, targetFolder))
                .thenReturn(spaceTarget);
        // requireSameSpace detects mismatch: personal vs team
        doThrow(new BusinessException(ErrorCode.FILE_STATE_INVALID, "不能在个人空间和团队空间之间移动或复制文件"))
                .when(fileAccessGuardService).requireSameSpace(anyList(), eq(10L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileMoveService.moveFiles(List.of(fileNodeId), targetParentId, 10L, userId));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能在个人空间和团队空间之间移动"));
    }

    // ==================== Move file without permission — should throw ====================

    @Test
    void moveFiles_noPermission_shouldThrow() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/team/test.txt");
        sourceFile.setParentId(50L);
        sourceFile.setTeamId(10L);
        sourceFile.setSpaceType(FileSpaceType.TEAM);

        // requireWriteAccess on source file throws
        doThrow(new BusinessException(ErrorCode.NO_PERMISSION, "无权访问该文件"))
                .when(fileAccessGuardService).requireWriteAccess(anyList(), eq(userId));

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileMoveService.moveFiles(List.of(fileNodeId), targetParentId, 10L, userId));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }

    // ==================== F10：单次移动的顶层节点上限 ====================

    /**
     * F10（P3 无界输入）：{@code moveFiles} 必须在<b>任何查询/权限校验之前</b>拒绝超过上限的请求。
     *
     * <h2>为什么需要</h2>
     * <p>复制路径早有 {@code app.file.copy.max-nodes-per-tx}（默认 500）护栏，移动路径完全没有：
     * {@code collectDescendantNodes} 会把整棵子树<b>全量预载入内存</b>，随后在<b>单个事务</b>内
     * 逐行 UPDATE 每个后代。一个「移动大目录」的请求 = 长事务 + 大结果集，占着 DB 连接并放大锁范围。</p>
     *
     * <p>关键断言：fail-fast —— 一项都不得被处理，否则「先预载全树再报错」依然会造成内存与连接占用。</p>
     */
    @Test
    void moveFiles_exceedingNodeLimit_shouldFailFastBeforeAnyQuery() {
        List<Long> tooMany = new java.util.ArrayList<>();
        for (long i = 0; i < MAX_NODES_PER_TX + 1; i++) {
            tooMany.add(i + 1);
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileMoveService.moveFiles(tooMany, 200L, 10L, 1L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(String.valueOf(MAX_NODES_PER_TX)),
                "错误信息必须给出实际上限，便于前端拆分批次；实际：" + ex.getMessage());
        // fail-fast：不得先做任何**数据查询/权限校验**（否则上限就只是"事后报错"而非防护）。
        // 注意 validateUserId / validateTargetParentId 是纯本地参数校验，位于上限检查之前属正常，
        // 因此这里不能对 fileDomainValidator 用 verifyNoInteractions。
        verify(fileDomainValidator, never()).requireMovableNodes(anyList());
        verifyNoInteractions(fileAccessGuardService);
        verifyNoInteractions(fileMapper);
        verifyNoInteractions(helper);
    }

    /** 边界对照：恰好等于上限不得被上限拒绝（避免写成「小于」而误伤合法批次）。 */
    @Test
    void moveFiles_atExactNodeLimit_shouldNotBeRejectedByLimit() {
        List<Long> atLimit = new java.util.ArrayList<>();
        for (long i = 0; i < MAX_NODES_PER_TX; i++) {
            atLimit.add(i + 1);
        }
        // 让后续校验抛一个**非上限**的异常，以此证明没有命中上限分支
        when(fileDomainValidator.requireMovableNodes(anyList()))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "文件不存在"));

        try {
            fileMoveService.moveFiles(atLimit, 200L, 10L, 1L);
        } catch (BusinessException e) {
            assertFalse(e.getMessage() != null && e.getMessage().contains("数量过多"),
                    "恰好 " + MAX_NODES_PER_TX + " 条不得被上限拒绝（边界写成了 > 而非 >=）：" + e.getMessage());
        }
    }
}
