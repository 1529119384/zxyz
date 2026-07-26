package uno.acloud.team.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.mapper.PermissionRoleMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock
    private PermissionRoleMapper permissionRoleMapper;

    @Mock
    private TeamPermissionCacheService teamPermissionCacheService;

    @Mock
    private UserRoleBindingService userRoleBindingService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PermissionService permissionService;

    // ==================== hasTeamPermission ====================

    @Test
    void hasTeamPermission_returnsFalseWhenTeamIdNull() {
        boolean result = permissionService.hasTeamPermission(10L, null, "team:file:read");
        assertFalse(result);
    }

    @Test
    void hasTeamPermission_returnsFalseWhenUserIdNull() {
        boolean result = permissionService.hasTeamPermission(null, 1L, "team:file:read");
        assertFalse(result);
    }

    @Test
    void hasTeamPermission_returnsFalseWhenPermissionCodeBlank() {
        boolean result = permissionService.hasTeamPermission(10L, 1L, "");
        assertFalse(result);
    }

    @Test
    void hasTeamPermission_returnsCachedValue() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = permissionService.hasTeamPermission(10L, 1L, "team:file:read");
        assertTrue(result);
        verifyNoInteractions(permissionRoleMapper);
    }

    @Test
    void hasTeamPermission_queriesDbWhenCacheNull() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = permissionService.hasTeamPermission(10L, 1L, "team:file:read");
        assertTrue(result);
        verify(permissionRoleMapper, never()).getTeamPermissionCodes(anyLong(), anyLong());
    }

    // ==================== getSystemRolesByUserId (delegates) ====================

    @Test
    void getSystemRolesByUserId_delegatesToBindingService() {
        when(userRoleBindingService.getSystemRolesByUserId(10L)).thenReturn(List.of(SystemRoleCodes.SYSTEM_ADMIN));

        List<String> result = permissionService.getSystemRolesByUserId(10L);
        assertEquals(List.of(SystemRoleCodes.SYSTEM_ADMIN), result);
    }

    // ==================== assignRoleToUser (delegates) ====================

    @Test
    void assignRoleToUser_delegatesToBindingService() {
        permissionService.assignRoleToUser(10L, SystemRoleCodes.SYSTEM_USER, 99L, "127.0.0.1");

        verify(userRoleBindingService).assignRoleToUser(10L, SystemRoleCodes.SYSTEM_USER, 99L, "127.0.0.1");
    }

    // ==================== ensureDefaultRole (delegates) ====================

    @Test
    void ensureDefaultRole_delegatesToBindingService() {
        permissionService.ensureDefaultRole(10L, "newuser");

        verify(userRoleBindingService).ensureDefaultRole(10L, "newuser");
    }

    // ==================== deleteSystemRole ====================

    @Test
    void deleteSystemRole_throwsWhenBuiltIn() {
        RoleEntity role = new RoleEntity();
        role.setId(1);
        role.setRoleCode(SystemRoleCodes.SYSTEM_ADMIN);
        role.setRoleName("系统管理员");

        when(permissionRoleMapper.getRoleById(1)).thenReturn(role);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> permissionService.deleteSystemRole(1, 99L, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void deleteSystemRole_throwsWhenUsersAssigned() {
        RoleEntity role = new RoleEntity();
        role.setId(5);
        role.setRoleCode("custom_role");
        role.setRoleName("Custom Role");

        when(permissionRoleMapper.getRoleById(5)).thenReturn(role);
        when(permissionRoleMapper.countUsersByRoleId(5)).thenReturn(3);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> permissionService.deleteSystemRole(5, 99L, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }
}
