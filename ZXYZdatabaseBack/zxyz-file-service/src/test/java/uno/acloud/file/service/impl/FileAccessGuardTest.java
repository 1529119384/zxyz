package uno.acloud.file.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.entity.FileItem;
import uno.acloud.file.infrastructure.entity.FileNode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileAccessGuardTest {

    @Mock
    private TeamServicePermissionClient teamServicePermissionClient;

    @Mock
    private ProjectAccessCacheService projectAccessCacheService;

    @InjectMocks
    private FileAccessGuard fileAccessGuard;

    // ---- helper methods ----

    private FileItem personalFile(Long id, Long uploadUserId) {
        FileItem node = FileItem.create();
        node.setId(id);
        node.setUploadUserId(uploadUserId);
        node.setTeamId(null);
        node.setSpaceType(FileSpaceType.PERSONAL);
        node.setProjectId(null);
        node.setDeleted(0);
        return node;
    }

    private FileItem teamFile(Long id, Long teamId) {
        FileItem node = FileItem.create();
        node.setId(id);
        node.setTeamId(teamId);
        node.setSpaceType(FileSpaceType.TEAM);
        node.setUploadUserId(99L);
        node.setProjectId(null);
        node.setDeleted(0);
        return node;
    }

    private FileItem projectFile(Long id, Long projectId) {
        FileItem node = FileItem.create();
        node.setId(id);
        node.setTeamId(10L);
        node.setSpaceType(FileSpaceType.PROJECT);
        node.setProjectId(projectId);
        node.setUploadUserId(99L);
        node.setDeleted(0);
        return node;
    }

    // ---- single-node tests ----

    @Test
    void requireReadAccess_singleNode_personalFile_ownerAccess() {
        FileItem node = personalFile(1L, 100L);
        assertDoesNotThrow(() -> fileAccessGuard.requireReadAccess(node, 100L));
        verifyNoInteractions(teamServicePermissionClient, projectAccessCacheService);
    }

    @Test
    void requireReadAccess_singleNode_personalFile_notOwner() {
        FileItem node = personalFile(1L, 100L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileAccessGuard.requireReadAccess(node, 200L));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }

    @Test
    void requireReadAccess_singleNode_personalFile_nullUserId() {
        FileItem node = personalFile(1L, 100L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileAccessGuard.requireReadAccess(node, null));
        assertEquals(ErrorCode.NO_LOGIN, ex.getErrorCode());
    }

    @Test
    void requireReadAccess_singleNode_teamFile_delegatesToTeamClient() {
        FileItem node = teamFile(1L, 10L);
        fileAccessGuard.requireReadAccess(node, 100L);
        verify(teamServicePermissionClient).check(100L, 10L, TeamPermissionCodes.TEAM_FILE_READ);
        verifyNoInteractions(projectAccessCacheService);
    }

    @Test
    void requireReadAccess_singleNode_projectFile_delegatesToProjectClient() {
        FileItem node = projectFile(1L, 50L);
        fileAccessGuard.requireReadAccess(node, 100L);
        verify(projectAccessCacheService).checkAccess(50L, 100L);
        verifyNoInteractions(teamServicePermissionClient);
    }

    @Test
    void requireReadAccess_singleNode_nullNode() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileAccessGuard.requireReadAccess((FileNode) null, 100L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ---- batch tests ----

    @Test
    void requireReadAccess_batchList_nullListReturnsSilently() {
        assertDoesNotThrow(() -> fileAccessGuard.requireReadAccess((List<FileNode>) null, 100L));
        verifyNoInteractions(teamServicePermissionClient, projectAccessCacheService);
    }

    @Test
    void requireWriteAccess_batchList_groupsAndDeduplicates() {
        // Two team files with the same teamId -> check() should be called once
        FileItem node1 = teamFile(1L, 10L);
        FileItem node2 = teamFile(2L, 10L);
        List<FileNode> nodes = Arrays.asList(node1, node2);

        fileAccessGuard.requireWriteAccess(nodes, 100L);
        verify(teamServicePermissionClient, times(1)).check(100L, 10L, TeamPermissionCodes.TEAM_FILE_WRITE);
    }

    @Test
    void requireDeleteAccess_batchList_personalFileNotOwner() {
        FileItem node = personalFile(1L, 100L);
        List<FileNode> nodes = Collections.singletonList(node);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileAccessGuard.requireDeleteAccess(nodes, 200L));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }

    // ---- requireSameSpace tests ----

    @Test
    void requireSameSpace_throwsWhenDifferentTeamIds() {
        FileItem node = teamFile(1L, 10L);
        List<FileNode> nodes = Collections.singletonList(node);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileAccessGuard.requireSameSpace(nodes, 20L));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
    }

    @Test
    void requireSameSpace_succeedsWhenSameTeamIds() {
        FileItem node = teamFile(1L, 10L);
        List<FileNode> nodes = Collections.singletonList(node);
        assertDoesNotThrow(() -> fileAccessGuard.requireSameSpace(nodes, 10L));
    }
}
