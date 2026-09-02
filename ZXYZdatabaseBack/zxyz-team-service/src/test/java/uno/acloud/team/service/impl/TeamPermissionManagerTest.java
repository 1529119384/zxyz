package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamRoleCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamPermissionMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamPermissionManagerTest {

    @Mock
    private TeamPermissionMapper mapper;
    @Mock
    private TeamPermissionCacheService teamPermissionCacheService;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private TeamPermissionManager manager;

    @BeforeEach
    void setUp() {
        manager = new TeamPermissionManager(mapper, teamPermissionCacheService, userServiceClient, stringRedisTemplate);
    }

    // ---- assignMemberRole tests ----

    @Test
    void assignMemberRole_shouldAssignRoleWhenRoleExists() {
        Long teamId = 1L;
        Long userId = 100L;
        String roleCode = TeamRoleCodes.MEMBER;

        when(mapper.getRoleId(teamId, roleCode)).thenReturn(10L);

        ArgumentCaptor<TransactionSynchronization> syncCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            manager.assignMemberRole(teamId, userId, roleCode);

            verify(mapper).deleteMemberRoles(teamId, userId);
            verify(mapper).insertMemberRole(teamId, userId, 10L);
            verify(teamPermissionCacheService).evictMember(teamId, userId);

            // Simulate post-commit callback
            mocked.verify(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            syncCaptor.getValue().afterCommit();
            verify(userServiceClient).clearPermissionCache(userId);
        }
    }

    @Test
    void assignMemberRole_shouldThrowWhenRoleNotFound() {
        Long teamId = 1L;
        Long userId = 100L;
        String roleCode = "nonexistent_role";

        when(mapper.getRoleId(teamId, roleCode)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> manager.assignMemberRole(teamId, userId, roleCode));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("角色不存在"));
        verify(mapper, never()).deleteMemberRoles(anyLong(), anyLong());
        verify(mapper, never()).insertMemberRole(anyLong(), anyLong(), anyLong());
    }

    @Test
    void assignMemberRole_shouldReplaceExistingRole() {
        Long teamId = 1L;
        Long userId = 100L;

        when(mapper.getRoleId(teamId, TeamRoleCodes.ADMIN)).thenReturn(20L);

        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            manager.assignMemberRole(teamId, userId, TeamRoleCodes.ADMIN);

            // Should delete old roles first, then insert new one
            verify(mapper).deleteMemberRoles(teamId, userId);
            verify(mapper).insertMemberRole(teamId, userId, 20L);
        }
    }

    // ---- clearMemberRole tests ----

    @Test
    void clearMemberRole_shouldDeleteAndEvictCache() {
        Long teamId = 1L;
        Long userId = 100L;

        ArgumentCaptor<TransactionSynchronization> syncCaptor = ArgumentCaptor.forClass(TransactionSynchronization.class);
        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            manager.clearMemberRole(teamId, userId);

            verify(mapper).deleteMemberRoles(teamId, userId);
            verify(teamPermissionCacheService).evictMember(teamId, userId);

            // Simulate post-commit callback
            mocked.verify(() -> TransactionSynchronizationManager.registerSynchronization(syncCaptor.capture()));
            syncCaptor.getValue().afterCommit();
            verify(userServiceClient).clearPermissionCache(userId);
        }
    }

    // ---- listRoleCodes tests ----

    @Test
    void listRoleCodes_shouldReturnEmptyForNullTeamId() {
        List<String> result = manager.listRoleCodes(null, 100L);
        assertTrue(result.isEmpty());
    }

    @Test
    void listRoleCodes_shouldReturnEmptyForNullUserId() {
        List<String> result = manager.listRoleCodes(1L, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void listRoleCodes_shouldReturnRolesWhenExists() {
        Long teamId = 1L;
        Long userId = 100L;

        // repairBuiltInRoles check: owner role exists
        when(mapper.getRoleId(teamId, TeamRoleCodes.OWNER)).thenReturn(1L);
        when(mapper.getTeamRoleCodes(userId, teamId)).thenReturn(List.of(TeamRoleCodes.ADMIN));

        List<String> result = manager.listRoleCodes(teamId, userId);

        assertEquals(1, result.size());
        assertEquals(TeamRoleCodes.ADMIN, result.get(0));
    }

    // ---- listPermissionCodes tests ----

    @Test
    void listPermissionCodes_shouldReturnEmptyForNullArgs() {
        assertTrue(manager.listPermissionCodes(null, 100L).isEmpty());
        assertTrue(manager.listPermissionCodes(1L, null).isEmpty());
    }

    @Test
    void listPermissionCodes_shouldReturnPermissionsWhenExists() {
        Long teamId = 1L;
        Long userId = 100L;

        when(mapper.getRoleId(teamId, TeamRoleCodes.OWNER)).thenReturn(1L);
        when(mapper.getTeamPermissionCodes(userId, teamId))
                .thenReturn(List.of("team_view", "team_file_read"));

        List<String> result = manager.listPermissionCodes(teamId, userId);

        assertEquals(2, result.size());
        assertTrue(result.contains("team_view"));
        assertTrue(result.contains("team_file_read"));
    }

    // ---- initializeBuiltInRoles tests ----

    @Test
    void initializeBuiltInRoles_shouldCreateRolesAndPermissions() {
        Long teamId = 1L;
        Long ownerUserId = 100L;

        // Mock for ensureBuiltInPermissions (upsert permissions)
        when(mapper.upsertPermission(any())).thenReturn(1);
        // Mock for ensureBuiltInRoles (upsert roles)
        when(mapper.upsertRole(any())).thenReturn(1);
        // Mock for assignBuiltInRolePermissions
        when(mapper.getRoleId(eq(teamId), anyString())).thenReturn(1L);
        when(mapper.getPermissionId(anyString())).thenReturn(1);
        when(mapper.insertRolePermission(anyLong(), anyLong(), anyInt())).thenReturn(1);
        // Mock for assignMemberRole
        when(mapper.deleteMemberRoles(teamId, ownerUserId)).thenReturn(1);
        when(mapper.insertMemberRole(teamId, ownerUserId, 1L)).thenReturn(1);

        try (MockedStatic<TransactionSynchronizationManager> mocked = mockStatic(TransactionSynchronizationManager.class)) {
            manager.initializeBuiltInRoles(teamId, ownerUserId);

            // Should upsert all built-in roles
            verify(mapper, atLeastOnce()).upsertRole(any());
            // Should assign owner role to the creator
            verify(mapper).deleteMemberRoles(teamId, ownerUserId);
            verify(mapper).insertMemberRole(teamId, ownerUserId, 1L);
        }
    }
}
