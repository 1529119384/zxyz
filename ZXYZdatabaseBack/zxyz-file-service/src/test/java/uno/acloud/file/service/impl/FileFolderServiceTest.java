package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileDeleteStatus;
import uno.acloud.common.FileNodeType;
import uno.acloud.common.FileSpaceType;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.Folder;
import uno.acloud.file.infrastructure.mapper.FileMapper;
import uno.acloud.file.vo.FolderCreateResultVO;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileFolderServiceTest {

    @Mock
    private FileMapper fileMapper;
    @Mock
    private FilePathResolver filePathResolver;
    @Mock
    private FileDomainValidator fileDomainValidator;
    @Mock
    private FileAccessGuard fileAccessGuardService;

    private FileFolderService service;

    @BeforeEach
    void setUp() {
        service = new FileFolderService(fileMapper, filePathResolver,
                fileDomainValidator, fileAccessGuardService);
    }

    // ---- createFolder (root level, parentId = -1) tests ----

    @Test
    void createFolder_shouldSucceedAtRootLevel() {
        Long parentId = -1L;
        Long teamId = 10L;
        Long userId = 100L;
        String folderName = "My Folder";

        // SpaceTarget.fromRequest: teamId=10, spaceType=TEAM, projectId=null
        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FOLDER),
                eq(folderName), any(), isNull()))
                .thenReturn("My Folder");
        when(filePathResolver.buildStorePath(parentId, "My Folder")).thenReturn("/My Folder");
        doAnswer(invocation -> {
            Folder folder = invocation.getArgument(0);
            folder.setId(500L);
            return 1;
        }).when(fileMapper).insertFolder(any(Folder.class));

        FolderCreateResultVO result = service.createFolder(folderName, parentId, teamId, userId);

        assertNotNull(result);
        assertEquals(500L, result.getId());
        assertEquals("My Folder", result.getOriginalName());
        assertEquals(parentId, result.getParentId());

        // Verify folder was built correctly
        ArgumentCaptor<Folder> captor = ArgumentCaptor.forClass(Folder.class);
        verify(fileMapper).insertFolder(captor.capture());
        Folder inserted = captor.getValue();
        assertEquals(FileNodeType.FOLDER, inserted.getFileType());
        assertEquals(teamId, inserted.getTeamId());
        assertEquals(userId, inserted.getUploadUserId());
        assertEquals(FileDeleteStatus.NORMAL, inserted.getDeleted());
        assertEquals(parentId, inserted.getParentId());
    }

    @Test
    void createFolder_shouldRenameOnDuplicateName() {
        Long parentId = -1L;
        Long teamId = 10L;
        Long userId = 100L;
        String folderName = "Existing";

        // Validator resolves to a new name
        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FOLDER),
                eq("Existing"), any(), isNull()))
                .thenReturn("Existing(1)");
        when(filePathResolver.buildStorePath(parentId, "Existing(1)")).thenReturn("/Existing(1)");
        doAnswer(invocation -> {
            Folder folder = invocation.getArgument(0);
            folder.setId(501L);
            return 1;
        }).when(fileMapper).insertFolder(any(Folder.class));

        FolderCreateResultVO result = service.createFolder(folderName, parentId, teamId, userId);

        assertEquals("Existing(1)", result.getOriginalName());
    }

    @Test
    void createFolder_shouldThrowWhenParentIdIsNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFolder("Folder", null, 10L, 100L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("parentId"));
    }

    @Test
    void createFolder_shouldThrowWhenInsertFails() {
        Long parentId = -1L;

        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FOLDER),
                eq("Folder"), any(), isNull()))
                .thenReturn("Folder");
        when(filePathResolver.buildStorePath(parentId, "Folder")).thenReturn("/Folder");
        when(fileMapper.insertFolder(any(Folder.class))).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFolder("Folder", parentId, 10L, 100L));

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("创建文件夹失败"));
    }

    @Test
    void createFolder_shouldSetProjectSpace_forProjectType() {
        Long parentId = -1L;
        Long teamId = 10L;
        Long projectId = 50L;
        Long userId = 100L;

        when(fileDomainValidator.resolveAvailableName(
                eq(parentId), any(SpaceTarget.class), eq(FileNodeType.FOLDER),
                eq("ProjFolder"), any(), isNull()))
                .thenReturn("ProjFolder");
        when(filePathResolver.buildStorePath(parentId, "ProjFolder")).thenReturn("/ProjFolder");
        doAnswer(invocation -> {
            Folder folder = invocation.getArgument(0);
            folder.setId(600L);
            return 1;
        }).when(fileMapper).insertFolder(any(Folder.class));

        FolderCreateResultVO result = service.createFolder(
                "ProjFolder", parentId, teamId, FileSpaceType.PROJECT, projectId, userId);

        assertEquals(600L, result.getId());

        ArgumentCaptor<Folder> captor = ArgumentCaptor.forClass(Folder.class);
        verify(fileMapper).insertFolder(captor.capture());
        Folder inserted = captor.getValue();
        assertEquals(FileSpaceType.PROJECT, inserted.getSpaceType());
        assertEquals(projectId, inserted.getProjectId());
    }
}
