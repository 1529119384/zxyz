package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.common.ProjectRoleCodes;
import uno.acloud.project.dto.project.AddProjectMemberRequest;
import uno.acloud.project.dto.project.TransferProjectLeaderRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectMember;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mq.ProjectEventPublisher;
import uno.acloud.project.service.ProjectAccessGuardPort;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectMemberServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectAccessGuardPort projectAccessGuard;

    @Mock
    private ProjectCommandSupport commandSupport;

    @Mock
    private ProjectViewAssembler viewAssembler;

    @Mock
    private ProjectEventPublisher eventPublisher;

    private ProjectMemberService projectMemberService;

    @BeforeEach
    void setUp() {
        projectMemberService = new ProjectMemberService(
                projectMapper, projectAccessGuard, commandSupport, viewAssembler, eventPublisher,
                null);
        // Self-injection for @Transactional proxy — in unit tests, point to the same instance
        projectMemberService.setSelf(projectMemberService);
    }

    private Project activeProject(Long id, Long teamId, Long leaderUserId) {
        Project project = new Project();
        project.setId(id);
        project.setTeamId(teamId);
        project.setLeaderUserId(leaderUserId);
        project.setStatus(0);
        return project;
    }

    // ==================== Add member — should succeed ====================

    @Test
    void addMember_validRequest_shouldSucceed() {
        Long projectId = 1L;
        Long operatorUserId = 10L;
        Long targetUserId = 20L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, operatorUserId);
        when(projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId)).thenReturn(project);

        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(targetUserId);

        ProjectMemberVO memberVO = new ProjectMemberVO(
                targetUserId, "targetUser", "Target User", null,
                ProjectRoleCodes.MEMBER, LocalDateTime.now());
        when(projectMapper.listMembers(projectId)).thenReturn(List.of());
        when(viewAssembler.toMemberVOList(anyList())).thenReturn(List.of(memberVO));

        ProjectMemberVO result = projectMemberService.addMember(projectId, request, operatorUserId);

        assertNotNull(result);
        assertEquals(targetUserId, result.getUserId());
        verify(commandSupport).requireActiveTeamMember(teamId, targetUserId);
        verify(commandSupport).upsertMember(eq(projectId), eq(targetUserId), eq(ProjectRoleCodes.MEMBER), any(LocalDateTime.class));
    }

    // ==================== Add duplicate member — should handle via upsert ====================

    @Test
    void addMember_duplicate_shouldHandleViaUpsert() {
        Long projectId = 1L;
        Long operatorUserId = 10L;
        Long existingUserId = 20L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, operatorUserId);
        when(projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId)).thenReturn(project);

        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(existingUserId);

        // Upsert handles duplicate via ON DUPLICATE KEY UPDATE
        ProjectMemberVO memberVO = new ProjectMemberVO(
                existingUserId, "existingUser", "Existing User", null,
                ProjectRoleCodes.MEMBER, LocalDateTime.now());
        when(projectMapper.listMembers(projectId)).thenReturn(List.of());
        when(viewAssembler.toMemberVOList(anyList())).thenReturn(List.of(memberVO));

        ProjectMemberVO result = projectMemberService.addMember(projectId, request, operatorUserId);

        assertNotNull(result);
        assertEquals(existingUserId, result.getUserId());
        // upsertMember is called (ON DUPLICATE KEY UPDATE handles the duplicate)
        verify(commandSupport).upsertMember(eq(projectId), eq(existingUserId), eq(ProjectRoleCodes.MEMBER), any(LocalDateTime.class));
    }

    // ==================== Remove member — not supported in current service ====================
    // ProjectMemberService does not have a removeMember method.
    // The upsert-based approach means members are managed via role changes.

    // ==================== Add member with null userId — should throw ====================

    @Test
    void addMember_nullUserId_shouldThrow() {
        Long projectId = 1L;
        Long operatorUserId = 10L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, operatorUserId);
        when(projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId)).thenReturn(project);

        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectMemberService.addMember(projectId, request, operatorUserId));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("userId 不能为空"));
    }

    // ==================== Transfer leader — should succeed ====================

    @Test
    void transferLeader_validRequest_shouldSucceed() {
        Long projectId = 1L;
        Long operatorUserId = 10L;
        Long newLeaderUserId = 20L;
        Long teamId = 100L;

        Project project = activeProject(projectId, teamId, operatorUserId);
        when(projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId)).thenReturn(project);

        TransferProjectLeaderRequest request = new TransferProjectLeaderRequest();
        request.setLeaderUserId(newLeaderUserId);

        ProjectVO projectVO = new ProjectVO(
                projectId, teamId, "TestProject", null, newLeaderUserId, null,
                0, null, null, null, true, true);
        when(viewAssembler.toProjectVO(any(Project.class), eq(operatorUserId))).thenReturn(projectVO);

        ProjectVO result = projectMemberService.transferLeader(projectId, request, operatorUserId);

        assertNotNull(result);
        assertEquals(newLeaderUserId, result.getLeaderUserId());
        verify(commandSupport).requireActiveTeamMember(teamId, newLeaderUserId);
        verify(commandSupport).upsertMember(eq(projectId), eq(newLeaderUserId), eq(ProjectRoleCodes.LEADER), any(LocalDateTime.class));
        verify(projectMapper).updateLeader(projectId, newLeaderUserId);
    }

    // ==================== Add member without manage access — should throw ====================

    @Test
    void addMember_noManageAccess_shouldThrow() {
        Long projectId = 1L;
        Long operatorUserId = 10L;

        when(projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId))
                .thenThrow(new BusinessException(ErrorCode.NO_PERMISSION, "无权管理该项目组"));

        AddProjectMemberRequest request = new AddProjectMemberRequest();
        request.setUserId(20L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectMemberService.addMember(projectId, request, operatorUserId));
        assertEquals(ErrorCode.NO_PERMISSION, ex.getErrorCode());
    }
}
