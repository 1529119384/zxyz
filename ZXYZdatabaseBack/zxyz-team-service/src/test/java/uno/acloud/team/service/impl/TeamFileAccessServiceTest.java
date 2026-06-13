package uno.acloud.team.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.mapper.TeamFileAccessMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TeamFileAccessServiceTest {

    @Mock
    private TeamFileAccessMapper teamFileAccessMapper;

    @Mock
    private TeamPermissionCacheService teamPermissionCacheService;

    @InjectMocks
    private TeamFileAccessService teamFileAccessService;

    // ==================== requireTeamMember ====================

    @Test
    void requireTeamMember_throwsWhenUserIdNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamFileAccessService.requireTeamMember(1L, null));
        assertEquals(ErrorCode.NO_LOGIN, ex.getErrorCode());
    }

    @Test
    void requireTeamMember_returnsSilentlyWhenTeamIdNull() {
        // teamId=null should return without exception, regardless of userId
        assertDoesNotThrow(() -> teamFileAccessService.requireTeamMember(null, 1L));
        verifyNoInteractions(teamFileAccessMapper);
    }

    @Test
    void requireTeamMember_throwsWhenNotMember() {
        when(teamFileAccessMapper.countActiveMember(1L, 10L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamFileAccessService.requireTeamMember(1L, 10L));
        assertEquals(ErrorCode.TEAM_PERMISSION_DENIED, ex.getErrorCode());
    }

    @Test
    void requireTeamMember_succeedsWhenActiveMember() {
        when(teamFileAccessMapper.countActiveMember(1L, 10L)).thenReturn(1);

        assertDoesNotThrow(() -> teamFileAccessService.requireTeamMember(1L, 10L));
    }

    // ==================== hasPermission ====================

    @Test
    void hasPermission_returnsTrueWhenTeamIdNull() {
        // teamId=null bypasses all checks and returns true (potential security concern)
        boolean result = teamFileAccessService.hasPermission(10L, null, "team:file:read");
        assertTrue(result);
        verifyNoInteractions(teamPermissionCacheService, teamFileAccessMapper);
    }

    @Test
    void hasPermission_returnsFalseWhenUserIdNull() {
        boolean result = teamFileAccessService.hasPermission(null, 1L, "team:file:read");
        assertFalse(result);
        verifyNoInteractions(teamPermissionCacheService, teamFileAccessMapper);
    }

    @Test
    void hasPermission_returnsCachedTrue() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = teamFileAccessService.hasPermission(10L, 1L, "team:file:read");
        assertTrue(result);
        verify(teamFileAccessMapper, never()).countPermission(anyLong(), anyLong(), anyString());
    }

    @Test
    void hasPermission_returnsCachedFalse() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(false);

        boolean result = teamFileAccessService.hasPermission(10L, 1L, "team:file:read");
        assertFalse(result);
        verify(teamFileAccessMapper, never()).countPermission(anyLong(), anyLong(), anyString());
    }

    @Test
    void hasPermission_queriesDbWhenCacheNull() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        boolean result = teamFileAccessService.hasPermission(10L, 1L, "team:file:read");
        assertTrue(result);
    }

    @Test
    void hasPermission_queriesDbAndCachesFalse() {
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(false);

        boolean result = teamFileAccessService.hasPermission(10L, 1L, "team:file:read");
        assertFalse(result);
    }

    // ==================== check ====================

    @Test
    void check_throwsWhenNotMember() {
        when(teamFileAccessMapper.countActiveMember(1L, 10L)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamFileAccessService.check(10L, 1L, "team:file:read"));
        assertEquals(ErrorCode.TEAM_PERMISSION_DENIED, ex.getErrorCode());
    }

    @Test
    void check_throwsWhenNoPermission() {
        // requireTeamMember passes
        when(teamFileAccessMapper.countActiveMember(1L, 10L)).thenReturn(1);
        // hasPermission returns false via cache
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> teamFileAccessService.check(10L, 1L, "team:file:read"));
        assertEquals(ErrorCode.TEAM_PERMISSION_DENIED, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("team:file:read"));
    }

    @Test
    void check_succeedsWhenMemberAndHasPermission() {
        when(teamFileAccessMapper.countActiveMember(1L, 10L)).thenReturn(1);
        when(teamPermissionCacheService.checkPermission(eq(1L), eq(10L), eq("team:file:read"), any()))
                .thenReturn(true);

        assertDoesNotThrow(() -> teamFileAccessService.check(10L, 1L, "team:file:read"));
    }
}
