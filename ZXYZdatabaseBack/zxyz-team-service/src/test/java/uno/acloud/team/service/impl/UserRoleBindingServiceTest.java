package uno.acloud.team.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.SystemRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.entity.RoleEntity;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.PermissionRoleMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRoleBindingServiceTest {

    @Mock
    private PermissionRoleMapper permissionRoleMapper;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserRoleBindingService userRoleBindingService;

    // ==================== assignRoleToUser ====================

    @Test
    void assignRoleToUser_throwsWhenUserIdInvalid_null() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userRoleBindingService.assignRoleToUser(null, "system_admin", 99L, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void assignRoleToUser_throwsWhenUserIdInvalid_zero() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userRoleBindingService.assignRoleToUser(0L, "system_admin", 99L, "127.0.0.1"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void assignRoleToUser_throwsWhenRoleNotFound() {
        when(permissionRoleMapper.getRoleByCode("nonexistent")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userRoleBindingService.assignRoleToUser(10L, "nonexistent", 99L, "127.0.0.1"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void assignRoleToUser_deletesExistingAndInsertsNew() {
        RoleEntity role = new RoleEntity();
        role.setId(2);
        role.setRoleCode(SystemRoleCodes.SYSTEM_USER);
        role.setRoleName("普通用户");

        when(permissionRoleMapper.getRoleByCode(SystemRoleCodes.SYSTEM_USER)).thenReturn(role);
        when(permissionRoleMapper.getRoleByUserID(10L)).thenReturn(List.of(SystemRoleCodes.SYSTEM_ADMIN));

        TransactionSynchronization[] captured = new TransactionSynchronization[1];
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        captured[0] = invocation.getArgument(0);
                        return null;
                    });

            userRoleBindingService.assignRoleToUser(10L, SystemRoleCodes.SYSTEM_USER, 99L, "127.0.0.1");

            verify(permissionRoleMapper).deleteUserRoles(10L);
            verify(permissionRoleMapper).insertUserRole(10L, 2);
            verify(userServiceClient, never()).clearPermissionCache(anyLong());
            assertNotNull(captured[0]);
            captured[0].afterCommit();
            verify(userServiceClient).clearPermissionCache(10L);
        }
    }

    // ==================== ensureDefaultRole ====================

    @Test
    void ensureDefaultRole_assignsAdminWhenNoUsersExist() {
        when(permissionRoleMapper.countUserRoles(10L)).thenReturn(0);
        when(permissionRoleMapper.countUsersByRoleCode(SystemRoleCodes.SYSTEM_ADMIN)).thenReturn(0);

        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(1);
        adminRole.setRoleCode(SystemRoleCodes.SYSTEM_ADMIN);
        adminRole.setRoleName("系统管理员");
        when(permissionRoleMapper.getRoleByCode(SystemRoleCodes.SYSTEM_ADMIN)).thenReturn(adminRole);

        TransactionSynchronization[] captured = new TransactionSynchronization[1];
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        captured[0] = invocation.getArgument(0);
                        return null;
                    });

            userRoleBindingService.ensureDefaultRole(10L, "newuser");

            verify(permissionRoleMapper).insertUserRole(10L, 1);
            verify(userServiceClient, never()).clearPermissionCache(anyLong());
            assertNotNull(captured[0]);
            captured[0].afterCommit();
            verify(userServiceClient).clearPermissionCache(10L);
        }
    }

    @Test
    void ensureDefaultRole_assignsUserWhenAdminExists() {
        when(permissionRoleMapper.countUserRoles(10L)).thenReturn(0);
        when(permissionRoleMapper.countUsersByRoleCode(SystemRoleCodes.SYSTEM_ADMIN)).thenReturn(1);

        RoleEntity userRole = new RoleEntity();
        userRole.setId(2);
        userRole.setRoleCode(SystemRoleCodes.SYSTEM_USER);
        userRole.setRoleName("普通用户");
        when(permissionRoleMapper.getRoleByCode(SystemRoleCodes.SYSTEM_USER)).thenReturn(userRole);

        TransactionSynchronization[] captured = new TransactionSynchronization[1];
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        captured[0] = invocation.getArgument(0);
                        return null;
                    });

            userRoleBindingService.ensureDefaultRole(10L, "newuser");

            verify(permissionRoleMapper).insertUserRole(10L, 2);
            verify(userServiceClient, never()).clearPermissionCache(anyLong());
            assertNotNull(captured[0]);
            captured[0].afterCommit();
            verify(userServiceClient).clearPermissionCache(10L);
        }
    }

    @Test
    void ensureDefaultRole_skipsWhenAlreadyHasRoles() {
        when(permissionRoleMapper.countUserRoles(10L)).thenReturn(1);

        userRoleBindingService.ensureDefaultRole(10L, "existinguser");

        verify(permissionRoleMapper, never()).countUsersByRoleCode(anyString());
        verify(permissionRoleMapper, never()).insertUserRole(anyLong(), anyInt());
    }

    // ==================== assignBootstrapAdminRole ====================

    @Test
    void assignBootstrapAdminRole_clearsCacheAfterCommit() {
        when(permissionRoleMapper.countUserRoles(10L)).thenReturn(0);

        RoleEntity adminRole = new RoleEntity();
        adminRole.setId(1);
        adminRole.setRoleCode(SystemRoleCodes.SYSTEM_ADMIN);
        when(permissionRoleMapper.getRoleByCode(SystemRoleCodes.SYSTEM_ADMIN)).thenReturn(adminRole);

        TransactionSynchronization[] captured = new TransactionSynchronization[1];
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            mocked.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        captured[0] = invocation.getArgument(0);
                        return null;
                    });

            userRoleBindingService.assignBootstrapAdminRole(10L);

            verify(permissionRoleMapper).insertUserRole(10L, 1);
            verify(userServiceClient, never()).clearPermissionCache(anyLong());
            assertNotNull(captured[0]);
            captured[0].afterCommit();
            verify(userServiceClient).clearPermissionCache(10L);
        }
    }
}
