package uno.acloud.team.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.permission.AssignTeamRolePermissionsRequest;
import uno.acloud.team.infrastructure.mapper.TeamEntityMapper;
import uno.acloud.team.mapper.TeamPermissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamRolePermissionServiceTest {

    @Mock
    private TeamPermissionMapper teamPermissionMapper;

    @Mock
    private TeamPermissionCacheService teamPermissionCacheService;

    @Mock
    private TeamEntityMapper teamEntityMapper;

    @Mock
    private RoleManagementService roleManagementService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TeamRolePermissionService teamRolePermissionService;

    // ==================== hasPermission ====================

    @Test
    void hasPermission_returnsCachedValue() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = teamRolePermissionService.hasPermission(1L, 10L, "team:file:read");
        assertTrue(result);
        verify(teamPermissionMapper, never()).countMemberPermission(anyLong(), anyLong(), anyString());
    }

    @Test
    void hasPermission_queriesDbWhenCacheNull() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = teamRolePermissionService.hasPermission(1L, 10L, "team:file:read");
        assertTrue(result);
        verify(teamPermissionMapper, never()).countMemberPermission(anyLong(), anyLong(), anyString());
    }

    @Test
    void hasPermission_cachesDbResult() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(false);

        boolean result = teamRolePermissionService.hasPermission(1L, 10L, "team:file:read");
        assertFalse(result);
    }

    // ==================== assignRolePermissions ====================

    @Test
    void assignRolePermissions_throwsWhenPermissionNotFound() {
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "权限不存在: nonexistent:perm"))
                .when(roleManagementService).assignRolePermissionsInternal(eq(1L), eq(1L), anyList());

        AssignTeamRolePermissionsRequest request = new AssignTeamRolePermissionsRequest();
        request.setPermissionCodes(List.of("nonexistent:perm"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamRolePermissionService.assignRolePermissions(1L, 1L, request, 99L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    // ==================== listAudit ====================

    @Test
    void listAudit_returnsEmptyWhenNoRecords() {
        when(teamPermissionMapper.listAudit(1L, 50)).thenReturn(List.of());
        when(teamEntityMapper.toTeamPermissionAuditVOList(List.of())).thenReturn(List.of());

        var result = teamRolePermissionService.listAudit(1L, null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
