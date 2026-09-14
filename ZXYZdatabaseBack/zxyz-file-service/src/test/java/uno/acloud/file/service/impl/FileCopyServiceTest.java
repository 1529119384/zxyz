package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileNodeType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.infrastructure.mapper.UsageLedgerMapper;
import uno.acloud.file.storage.StorageProvider;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.vo.BatchOperationDetailVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileCopyServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileDomainValidator fileDomainValidator;

    @Mock
    private FilePathResolver filePathResolver;

    @Mock
    private FileAccessGuard fileAccessGuardService;

    @Mock
    private FileObjectReferenceManager fileObjectReferenceService;

    @Mock
    private FileOperationHelper helper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private ProjectStorageCheckClient projectStorageCheckClient;

    @Mock
    private UsageLedgerMapper usageLedgerMapper;

    @Mock
    private StorageProviderRegistry storageProviderRegistry;

    private FileCopyService fileCopyService;

    @BeforeEach
    void setUp() {
        fileCopyService = new FileCopyService(
                fileMapper, fileDomainValidator, filePathResolver,
                fileAccessGuardService, fileObjectReferenceService, helper, transactionTemplate, 500,
                projectStorageCheckClient, usageLedgerMapper, storageProviderRegistry);
        // Mock TransactionTemplate to execute lambdas directly
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        // 默认配额充足；个别用例可覆盖为 0 模拟超限
        lenient().when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(1);
    }

    // ==================== copyFiles — single file success ====================

    @Test
    void copyFiles_singleFile_shouldSucceed() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/test.txt");
        sourceFile.setUploadUserId(userId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(2);
        sourceFile.setUuidName("uuid-test.txt");
        sourceFile.setFileSize(1024L);
        sourceFile.setStorageProvider("oss");

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        // Validator stubs
        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);

        // Helper stubs
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(helper.resolveCopyName(eq(sourceFile), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("test.txt");
        when(helper.isRenamed(sourceFile, "test.txt")).thenReturn(false);
        when(helper.buildDetail(eq(sourceFile), anyString(), eq("test.txt"), eq(false),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        fileNodeId, "test.txt", FileNodeType.FILE, "copied",
                        false, "test.txt", "success", ErrorCode.SUCCESS, "success"));
        BatchOperationDetailVO expectedResult = new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of());
        when(helper.buildBatchResult(anyList(), eq(targetParentId))).thenReturn(expectedResult);

        // Mapper: insertFileItem succeeds
        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 1000L);
            return 1;
        });

        BatchOperationDetailVO result = fileCopyService.copyFiles(
                List.of(fileNodeId), targetParentId, teamId, userId);

        assertNotNull(result);
        // 副本行必须带非空 storage_provider：insertFileItem 的 INSERT 显式绑定该列，
        // 实体字段为 null 会落库为字面量 NULL（列默认值 'oss' 不生效）。
        verify(fileMapper).insertFileItem(argThat(item -> "oss".equals(item.getStorageProvider())));
        // 引用计数用的 provider 必须与落库值同源，两者不得再分叉。
        verify(fileObjectReferenceService).retainReference(eq("uuid-test.txt"), eq("oss"));
    }

    // ============ copyFiles — 复制后必须失效用量缓存（用户报障：容量未及时刷新） ============

    @Test
    void copyFiles_shouldPublishCreatedNodeIdsForCacheInvalidation() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;
        Long teamId = 10L;
        Long createdNodeId = 1000L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("test.txt");
        sourceFile.setStorePath("/test.txt");
        sourceFile.setUploadUserId(userId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(2);
        sourceFile.setUuidName("uuid-test.txt");
        sourceFile.setFileSize(1024L);
        sourceFile.setStorageProvider("oss");

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);
        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(helper.resolveCopyName(eq(sourceFile), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("test.txt");
        when(helper.isRenamed(sourceFile, "test.txt")).thenReturn(false);
        when(helper.buildDetail(any(FileNode.class), anyString(), anyString(), anyBoolean(),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        fileNodeId, "test.txt", FileNodeType.FILE, "copied",
                        false, "test.txt", "success", ErrorCode.SUCCESS, "success"));
        when(helper.buildBatchResult(anyList(), eq(targetParentId)))
                .thenReturn(new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of()));
        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", createdNodeId);
            return 1;
        });

        fileCopyService.copyFiles(List.of(fileNodeId), targetParentId, teamId, userId);

        // 复制新增了 file_node 行 ⇒ SUM(file_size) 变化，必须用「新建节点 id」失效用量缓存并广播变更事件。
        verify(helper).publishByIdsAfterCommit(FileOperationHelper.ACTION_COPIED, List.of(createdNodeId));
    }

    @Test
    void copyFiles_partialBatchFailure_shouldStillInvalidateCommittedBatches() {
        Long userId = 1L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);
        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        // 31 个顶层文件 → 分两批（30 + 1），第二批配额失败
        List<FileNode> topLevel = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            FileItem item = FileItem.create();
            item.setId((long) (100 + i));
            item.setOriginalName("f" + i + ".txt");
            // 必须设置 storePath：FilePathUtil.reduceToTopLevelNodes 会用 safeStorePath 校验路径
            // （null/空白直接抛 BusinessException），否则本用例会在进入批次循环前就中断。
            item.setStorePath("/f" + i + ".txt");
            item.setUploadUserId(userId);
            item.setTeamId(teamId);
            item.setSpaceType(2);
            item.setFileSize(10L);
            // 必须设置 uuidName + storageProvider：真实文件行必然有物理对象（uuid_name 非空）
            // 与存储后端；cloneFileItem 用它们做引用计数，空 provider 在生产路径上是 fail-fast
            // （FileObjectReferenceManager 会直接抛 BusinessException，不是静默跳过）。
            item.setUuidName("uuid-f" + i + ".txt");
            item.setStorageProvider("oss");
            topLevel.add(item);
        }

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(topLevel);
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(helper.resolveCopyName(any(FileNode.class), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("copy.txt");
        when(helper.isRenamed(any(FileNode.class), anyString())).thenReturn(false);
        when(helper.buildDetail(any(FileNode.class), anyString(), anyString(), anyBoolean(),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        1L, "copy.txt", FileNodeType.FILE, "copied",
                        false, "copy.txt", "success", ErrorCode.SUCCESS, "success"));
        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 9000L);
            return 1;
        });
        // 第一批扣减成功，第二批超限 → 第一批已提交、第二批整体回滚
        when(usageLedgerMapper.incrementWhenUnderLimit(anyString(), anyLong())).thenReturn(1, 0);
        when(helper.withBatchData(any(BusinessException.class), anyList(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<Long> sourceIds = topLevel.stream().map(FileNode::getId).toList();
        assertThrows(BusinessException.class, () ->
                fileCopyService.copyFiles(sourceIds, targetParentId, teamId, userId));

        // 已提交的第一批必须失效（否则这 30 个节点的用量永久停在旧值），且不得把回滚批次的 id 算进去。
        verify(helper).publishByIdsAfterCommit(eq(FileOperationHelper.ACTION_COPIED),
                argThat(ids -> ids != null && ids.size() == 30));
    }

    // ==================== copyFiles — exceeding MAX_COPY_NODES_PER_TRANSACTION ====================

    @Test
    void copyFiles_exceedingMaxNodes_shouldReject() {
        Long userId = 1L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        Folder sourceFolder = Folder.create();
        sourceFolder.setId(50L);
        sourceFolder.setOriginalName("bigFolder");
        sourceFolder.setStorePath("/bigFolder");
        sourceFolder.setUploadUserId(userId);
        sourceFolder.setTeamId(teamId);
        sourceFolder.setSpaceType(2);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFolder));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);

        // 501 descendant nodes → exceeds limit of 500
        List<FileNode> descendants = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            FileItem child = FileItem.create();
            child.setId((long) (1000 + i));
            child.setOriginalName("file" + i + ".txt");
            child.setParentId(50L);
            descendants.add(child);
        }
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(descendants);
        when(helper.buildChildrenMap(anyList())).thenAnswer(invocation -> {
            List<FileNode> nodes = invocation.getArgument(0);
            Map<Long, List<FileNode>> map = new java.util.HashMap<>();
            for (FileNode node : nodes) {
                map.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
            return map;
        });

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileCopyService.copyFiles(List.of(50L), targetParentId, teamId, userId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("502")); // 1 top-level + 501 children
    }

    // ==================== copyFiles — name conflict should rename ====================

    @Test
    void copyFiles_nameConflict_shouldRename() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("doc.txt");
        sourceFile.setStorePath("/doc.txt");
        sourceFile.setUploadUserId(userId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(2);
        sourceFile.setUuidName("uuid-doc.txt");
        sourceFile.setFileSize(512L);
        sourceFile.setStorageProvider("oss");

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        // Name conflict → renamed to doc(1).txt
        when(helper.resolveCopyName(eq(sourceFile), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("doc(1).txt");
        when(helper.isRenamed(sourceFile, "doc(1).txt")).thenReturn(true);
        when(helper.buildDetail(eq(sourceFile), anyString(), eq("doc(1).txt"), eq(true),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        fileNodeId, "doc.txt", FileNodeType.FILE, "copied",
                        true, "doc(1).txt", "success", ErrorCode.SUCCESS, "success"));
        BatchOperationDetailVO expectedResult = new BatchOperationDetailVO(1, 1, 0, 0, 1, targetParentId, List.of());
        when(helper.buildBatchResult(anyList(), eq(targetParentId))).thenReturn(expectedResult);

        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 1001L);
            return 1;
        });

        BatchOperationDetailVO result = fileCopyService.copyFiles(
                List.of(fileNodeId), targetParentId, teamId, userId);

        assertNotNull(result);
        // Verify the cloned file has the renamed original name
        verify(fileMapper).insertFileItem(argThat(item ->
                "doc(1).txt".equals(item.getOriginalName())));
        verify(fileObjectReferenceService).retainReference(eq("uuid-doc.txt"), eq("oss"));
    }

    // ==================== copyFiles — empty file list should reject ====================

    @Test
    void copyFiles_emptyFileIds_shouldReject() {
        Long userId = 1L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        // requireMovableNodes with empty/null list should throw
        when(fileDomainValidator.requireMovableNodes(anyList()))
                .thenThrow(new BusinessException(ErrorCode.BAD_REQUEST, "请选择要复制的文件"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileCopyService.copyFiles(List.of(), targetParentId, teamId, userId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    // ==================== copyFiles — folder with children within limit ====================

    @Test
    void copyFiles_folderWithChildrenWithinLimit_shouldPassLimitCheck() {
        Long userId = 1L;
        Long folderId = 50L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        Folder sourceFolder = Folder.create();
        sourceFolder.setId(folderId);
        sourceFolder.setOriginalName("myFolder");
        sourceFolder.setStorePath("/myFolder");
        sourceFolder.setUploadUserId(userId);
        sourceFolder.setTeamId(teamId);
        sourceFolder.setSpaceType(2);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFolder));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);

        // 3 child files — well within limit of 500
        List<FileNode> descendants = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            FileItem child = FileItem.create();
            child.setId((long) (1000 + i));
            child.setOriginalName("file" + i + ".txt");
            child.setParentId(folderId);
            child.setUuidName("uuid-" + i);
            descendants.add(child);
        }
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(descendants);
        when(helper.buildChildrenMap(anyList())).thenAnswer(invocation -> {
            List<FileNode> nodes = invocation.getArgument(0);
            Map<Long, List<FileNode>> map = new java.util.HashMap<>();
            for (FileNode node : nodes) {
                map.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
            return map;
        });

        // Stub copy name resolution
        when(helper.resolveCopyName(eq(sourceFolder), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("myFolder");
        when(helper.isRenamed(sourceFolder, "myFolder")).thenReturn(false);
        when(helper.buildDetail(eq(sourceFolder), anyString(), eq("myFolder"), eq(false),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        folderId, "myFolder", FileNodeType.FOLDER, "copied",
                        false, "myFolder", "success", ErrorCode.SUCCESS, "success"));
        BatchOperationDetailVO expectedResult = new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of());
        when(helper.buildBatchResult(anyList(), eq(targetParentId))).thenReturn(expectedResult);

        // Folder insert succeeds
        when(fileMapper.insertFolder(any(Folder.class))).thenAnswer(invocation -> {
            Folder f = invocation.getArgument(0);
            ReflectionTestUtils.setField(f, "id", 500L);
            return 1;
        });

        // Should NOT throw — 4 total nodes (1 folder + 3 children) is within limit
        BatchOperationDetailVO result = fileCopyService.copyFiles(
                List.of(folderId), targetParentId, teamId, userId);

        assertNotNull(result);
        // Verify folder was inserted (the limit check passed and copy proceeded)
        verify(fileMapper).insertFolder(any(Folder.class));
    }

    // ==================== copyFiles — one over MAX_COPY_NODES_PER_TRANSACTION limit ====================

    @Test
    void copyFiles_oneOverMaxNodes_shouldReject() {
        Long userId = 1L;
        Long folderId = 50L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        Folder sourceFolder = Folder.create();
        sourceFolder.setId(folderId);
        sourceFolder.setOriginalName("bigFolder");
        sourceFolder.setStorePath("/bigFolder");
        sourceFolder.setUploadUserId(userId);
        sourceFolder.setTeamId(teamId);
        sourceFolder.setSpaceType(2);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setOriginalName("target");
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);

        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFolder));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);

        // 500 children → total = 1 top-level + 500 children = 501 (one over limit of 500)
        List<FileNode> descendants = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            FileItem child = FileItem.create();
            child.setId((long) (1000 + i));
            child.setOriginalName("file" + i + ".txt");
            child.setParentId(folderId);
            descendants.add(child);
        }
        when(fileMapper.collectDescendantNodes(anyList())).thenReturn(descendants);
        when(helper.buildChildrenMap(anyList())).thenAnswer(invocation -> {
            List<FileNode> nodes = invocation.getArgument(0);
            Map<Long, List<FileNode>> map = new java.util.HashMap<>();
            for (FileNode node : nodes) {
                map.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
            return map;
        });

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileCopyService.copyFiles(List.of(folderId), targetParentId, teamId, userId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("501")); // 1 top-level + 500 children
    }

    // ============ 回归：副本必须继承源的 storage_provider，不能落库为 NULL ============
    //
    // 背景：FileMapper.insertFileItem 的 INSERT 语句显式列出了 storage_provider 列，
    // 因此实体字段为 null 时写入的是字面量 NULL，列默认值 'oss' 不会生效。
    // 该副本一旦再被复制，FileObjectReferenceManager#retainReference 会以
    // 「storageProvider 不能为空」直接抛 BusinessException ⇒ 副本无法再被复制
    // （用户报障：#121 复制文件夹后副本相关操作失败）。
    // 这组用例在修复前必然失败 —— 因为单元测试里 fileObjectReferenceService 是 mock，
    // 空 provider 不会抛异常，缺陷被静默吞掉。

    @Test
    void copyFiles_cloneInheritsSourceStorageProviderInsteadOfNull() {
        Long userId = 1L;
        Long fileNodeId = 100L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        FileItem sourceFile = FileItem.create();
        sourceFile.setId(fileNodeId);
        sourceFile.setOriginalName("a.txt");
        sourceFile.setStorePath("/a.txt");
        sourceFile.setUploadUserId(userId);
        sourceFile.setTeamId(teamId);
        sourceFile.setSpaceType(2);
        sourceFile.setUuidName("uuid-a.txt");
        sourceFile.setFileSize(1L);
        // 刻意用非默认值：证明副本继承的是「源」的 provider，而不是被默认值覆盖
        sourceFile.setStorageProvider("local");

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);
        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(sourceFile));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(helper.resolveCopyName(eq(sourceFile), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("a.txt");
        when(helper.isRenamed(sourceFile, "a.txt")).thenReturn(false);
        when(helper.buildDetail(any(FileNode.class), anyString(), anyString(), anyBoolean(),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        fileNodeId, "a.txt", FileNodeType.FILE, "copied",
                        false, "a.txt", "success", ErrorCode.SUCCESS, "success"));
        when(helper.buildBatchResult(anyList(), eq(targetParentId)))
                .thenReturn(new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of()));
        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 1000L);
            return 1;
        });

        fileCopyService.copyFiles(List.of(fileNodeId), targetParentId, teamId, userId);

        verify(fileMapper).insertFileItem(argThat(item -> "local".equals(item.getStorageProvider())));
        verify(fileObjectReferenceService).retainReference(eq("uuid-a.txt"), eq("local"));
    }

    @Test
    void copyFiles_nullSourceStorageProviderFallsBackToDefaultProvider() {
        Long userId = 1L;
        Long fileNodeId = 101L;
        Long targetParentId = 200L;
        Long teamId = 10L;

        // 存量行，或由旧版本克隆出来的副本：storage_provider 为 NULL
        FileItem legacySource = FileItem.create();
        legacySource.setId(fileNodeId);
        legacySource.setOriginalName("legacy.txt");
        legacySource.setStorePath("/legacy.txt");
        legacySource.setUploadUserId(userId);
        legacySource.setTeamId(teamId);
        legacySource.setSpaceType(2);
        legacySource.setUuidName("uuid-legacy.txt");
        legacySource.setFileSize(1L);
        legacySource.setStorageProvider(null);

        StorageProvider defaultProvider = mock(StorageProvider.class);
        when(defaultProvider.providerId()).thenReturn("oss");
        when(storageProviderRegistry.getDefaultProvider()).thenReturn(defaultProvider);

        Folder targetFolder = Folder.create();
        targetFolder.setId(targetParentId);
        targetFolder.setStorePath("/target");
        targetFolder.setTeamId(teamId);
        targetFolder.setSpaceType(2);
        SpaceTarget spaceTarget = new SpaceTarget(teamId, 2, null);

        when(fileDomainValidator.requireMovableNodes(anyList())).thenReturn(List.of(legacySource));
        when(fileDomainValidator.requireTargetFolder(targetParentId)).thenReturn(targetFolder);
        when(helper.resolveOperationTarget(targetParentId, teamId, null, null, targetFolder))
                .thenReturn(spaceTarget);
        when(helper.resolveCopyName(eq(legacySource), any(FileOperationHelper.CopyTargetContext.class), eq(userId)))
                .thenReturn("legacy.txt");
        when(helper.isRenamed(legacySource, "legacy.txt")).thenReturn(false);
        when(helper.buildDetail(any(FileNode.class), anyString(), anyString(), anyBoolean(),
                anyString(), anyInt(), anyString()))
                .thenReturn(new BatchOperationDetailVO.ItemDetail(
                        fileNodeId, "legacy.txt", FileNodeType.FILE, "copied",
                        false, "legacy.txt", "success", ErrorCode.SUCCESS, "success"));
        when(helper.buildBatchResult(anyList(), eq(targetParentId)))
                .thenReturn(new BatchOperationDetailVO(1, 1, 0, 0, 0, targetParentId, List.of()));
        when(fileMapper.insertFileItem(any(FileItem.class))).thenAnswer(invocation -> {
            FileItem item = invocation.getArgument(0);
            ReflectionTestUtils.setField(item, "id", 1001L);
            return 1;
        });

        fileCopyService.copyFiles(List.of(fileNodeId), targetParentId, teamId, userId);

        // 修复前：副本 provider 落库为 NULL 且 retainReference 收到 null（真跑时直接抛异常）
        verify(fileMapper).insertFileItem(argThat(item -> "oss".equals(item.getStorageProvider())));
        verify(fileObjectReferenceService).retainReference(eq("uuid-legacy.txt"), eq("oss"));
        verify(storageProviderRegistry).getDefaultProvider();
    }
}
