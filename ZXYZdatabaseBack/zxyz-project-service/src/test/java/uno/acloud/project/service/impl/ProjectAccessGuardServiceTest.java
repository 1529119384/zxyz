package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.entity.Project;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.service.TeamFileAccessPort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uno.acloud.common.ProjectErrorCode.*;

@ExtendWith(MockitoExtension.class)
class ProjectAccessGuardServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private TeamFileAccessPort teamFileAccessService;

    private ProjectAccessGuardService projectAccessGuardService;

    @BeforeEach
    void setUp() {
        projectAccessGuardService = new ProjectAccessGuardService(projectMapper, teamFileAccessService);
    }

    private Project activeProject(Long id, Long teamId, Long leaderUserId) {
        Project project = new Project();
        project.setId(id);
        project.setTeamId(teamId);
        project.setLeaderUserId(leaderUserId);
        project.setStatus(0); // active
        return project;
    }

    private Project archivedProject(Long id, Long teamId) {
        Project project = new Project();
        project.setId(id);
        project.setTeamId(teamId);
        project.setStatus(1); // archived
        return project;
    }

    // ==================== Access as project member — should pass ====================

    @Test
    void requireProjectAccess_member_shouldPass() {
        Long projectId = 1L;
        Long userId = 10L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, 99L);
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(projectMapper.countMember(projectId, userId)).thenReturn(1);

        Project result = projectAccessGuardService.requireProjectAccess(projectId, userId);

        assertNotNull(result);
        assertEquals(projectId, result.getId());
        verify(projectMapper).countMember(projectId, userId);
        // Team-level permission check should NOT be called (member check passed first)
        verify(teamFileAccessService, never()).hasPermission(anyLong(), anyLong(), anyString());
    }

    // ==================== Access as non-member — should throw ====================

    @Test
    void requireProjectAccess_nonMember_shouldThrow() {
        Long projectId = 1L;
        Long userId = 10L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, 99L);
        when(projectMapper.selectById(projectId)).thenReturn(project);
        when(projectMapper.countMember(projectId, userId)).thenReturn(0);
        when(teamFileAccessService.hasPermission(userId, teamId, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectAccessGuardService.requireProjectAccess(projectId, userId));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("无权访问该项目组"));
    }

    // ==================== Access to archived project — should throw ====================

    @Test
    void requireProjectAccess_archivedProject_shouldThrow() {
        Long projectId = 1L;
        Long userId = 10L;
        Long teamId = 100L;

        Project project = archivedProject(projectId, teamId);
        when(projectMapper.selectById(projectId)).thenReturn(project);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectAccessGuardService.requireProjectAccess(projectId, userId));
        assertEquals(ErrorCode.FILE_STATE_INVALID, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("项目组已归档"));
    }

    // ==================== Access to non-existent project — should throw ====================

    @Test
    void requireProjectAccess_nonExistentProject_shouldThrow() {
        Long projectId = 999L;
        Long userId = 10L;

        when(projectMapper.selectById(projectId)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectAccessGuardService.requireProjectAccess(projectId, userId));
        assertEquals(PROJECT_NOT_FOUND.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("项目组不存在"));
    }
}
