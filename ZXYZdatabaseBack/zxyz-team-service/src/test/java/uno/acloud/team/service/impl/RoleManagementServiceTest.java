package uno.acloud.team.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import static uno.acloud.common.TeamErrorCode.*;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.permission.AssignTeamMemberRoleRequest;
import uno.acloud.team.entity.TeamMember;
import uno.acloud.team.entity.TeamRoleEntity;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoleManagementServiceTest {

    @Mock
    private TeamPermissionMapper teamPermissionMapper;

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private TeamPermissionCacheService teamPermissionCacheService;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private RoleManagementService roleManagementService;

    // ==================== assignMemberRole ====================

    @Test
    void assignMemberRole_throwsWhenUserIdNull() {
        AssignTeamMemberRoleRequest request = new AssignTeamMemberRoleRequest();
        request.setUserId(null);
        request.setRoleCode("team_member");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleManagementService.assignMemberRole(1L, request, 99L));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void assignMemberRole_throwsWhenUserNotInTeam() {
        AssignTeamMemberRoleRequest request = new AssignTeamMemberRoleRequest();
        request.setUserId(10L);
        request.setRoleCode("team_member");

        when(teamMapper.getActiveMember(1L, 10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleManagementService.assignMemberRole(1L, request, 99L));
        assertEquals(TEAM_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void assignMemberRole_throwsWhenRoleNotFound() {
        AssignTeamMemberRoleRequest request = new AssignTeamMemberRoleRequest();
        request.setUserId(10L);
        request.setRoleCode("nonexistent_role");

        TeamMember member = new TeamMember();
        when(teamMapper.getActiveMember(1L, 10L)).thenReturn(member);
        when(teamPermissionMapper.getMemberRoleCode(1L, 10L)).thenReturn(null);
        when(teamPermissionMapper.getRoleByCode(1L, "nonexistent_role")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleManagementService.assignMemberRole(1L, request, 99L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ==================== deleteRole ====================

    @Test
    void deleteRole_throwsWhenBuiltIn() {
        TeamRoleEntity role = new TeamRoleEntity();
        role.setId(1L);
        role.setTeamId(1L);
        role.setRoleCode("team_owner");
        role.setBuiltin(1);

        when(teamPermissionMapper.getRoleById(1L, 1L)).thenReturn(role);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleManagementService.deleteRole(1L, 1L, 99L));
        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
    }

    @Test
    void deleteRole_succeedsForCustomRole() {
        TeamRoleEntity role = new TeamRoleEntity();
        role.setId(5L);
        role.setTeamId(1L);
        role.setRoleCode("custom_role");
        role.setBuiltin(0);

        when(teamPermissionMapper.getRoleById(1L, 5L)).thenReturn(role);

        roleManagementService.deleteRole(1L, 5L, 99L);

        verify(teamPermissionMapper).deleteRolePermissions(1L, 5L);
        verify(teamPermissionMapper).deleteRole(1L, 5L);
        verify(teamPermissionCacheService).evictTeam(1L);
    }

    // ==================== saveRole ====================

    @Test
    void saveRole_throwsWhenBuiltInCodeChanged() {
        TeamRoleEntity existingRole = new TeamRoleEntity();
        existingRole.setId(1L);
        existingRole.setTeamId(1L);
        existingRole.setRoleCode("team_owner");
        existingRole.setBuiltin(1);

        when(teamPermissionMapper.getRoleById(1L, 1L)).thenReturn(existingRole);

        var request = new uno.acloud.team.dto.permission.SaveTeamRoleRequest();
        request.setRoleCode("team_owner");
        request.setRoleName("Owner Updated");
        request.setDescription("Updated");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> roleManagementService.saveRole(1L, 1L, request, 99L));
        assertEquals(TEAM_PERMISSION_DENIED.getCode(), ex.getErrorCode());
    }

    // ==================== clearMemberRole ====================

    @Test
    void clearMemberRole_evictsCacheAndCallsUserService() {
        ArgumentCaptor<TransactionSynchronization> syncCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            roleManagementService.clearMemberRole(1L, 10L);

            verify(teamPermissionMapper).deleteMemberRoles(1L, 10L);
            verify(teamPermissionCacheService).evictMember(1L, 10L);

            mocked.verify(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            syncCaptor.getValue().afterCommit();
            verify(userServiceClient).clearPermissionCache(10L);
        }
    }
}
