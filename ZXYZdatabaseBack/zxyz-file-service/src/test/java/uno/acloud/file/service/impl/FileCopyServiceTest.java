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
import uno.acloud.common.config.ConfigGetter;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
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
    private ConfigGetter configGetter;

    private FileCopyService fileCopyService;

    @BeforeEach
    void setUp() {
        when(configGetter.getInt(eq("app.file.copy.max-nodes-per-tx"), anyInt())).thenReturn(500);
        fileCopyService = new FileCopyService(
                fileMapper, fileDomainValidator, filePathResolver,
                fileAccessGuardService, fileObjectReferenceService, helper, transactionTemplate, configGetter);
        // Mock TransactionTemplate to execute lambdas directly
        lenient().doAnswer(invocation -> {
            java.util.function.Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
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
        verify(fileMapper).insertFileItem(any(FileItem.class));
        verify(fileObjectReferenceService).retainReference("uuid-test.txt");
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
        verify(fileObjectReferenceService).retainReference("uuid-doc.txt");
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
}
