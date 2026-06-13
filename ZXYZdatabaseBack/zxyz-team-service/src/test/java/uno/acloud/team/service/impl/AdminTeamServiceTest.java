package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminTeamServiceTest {

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private TeamQuotaMapper teamQuotaMapper;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private ImSystemNotificationClient imSystemNotificationClient;

    @Mock
    private EmailServiceClient emailServiceClient;

    private AdminTeamService adminTeamService;

    @BeforeEach
    void setUp() {
        adminTeamService = new AdminTeamService(
                teamMapper, teamQuotaMapper, userServiceClient,
                fileServiceClient, imSystemNotificationClient, emailServiceClient);
    }

    // ==================== listTeams — batch fetch (N+1 fix verification) ====================

    @Test
    void listTeams_withBatchFetch_shouldCallUserServiceClientOnce() {
        // Two teams with different owners
        AdminTeamOverviewVO team1 = new AdminTeamOverviewVO(
                1L, "Team A", "desc A", 100L, null, 5, 100, 10737418240L, null, LocalDateTime.now());
        AdminTeamOverviewVO team2 = new AdminTeamOverviewVO(
                2L, "Team B", "desc B", 200L, null, 3, 50, 5368709120L, null, LocalDateTime.now());
        AdminTeamOverviewVO team3 = new AdminTeamOverviewVO(
                3L, "Team C", "desc C", 100L, null, 2, 80, 2147483648L, null, LocalDateTime.now());
        // team1 and team3 share the same owner (100L)

        when(teamMapper.listAdminTeamOverviews()).thenReturn(List.of(team1, team2, team3));

        // Batch user fetch — should be called once with distinct owner IDs [100, 200]
        UserInfoDTO owner100 = new UserInfoDTO();
        owner100.setId(100L);
        owner100.setUsername("owner_a");
        UserInfoDTO owner200 = new UserInfoDTO();
        owner200.setId(200L);
        owner200.setUsername("owner_b");
        when(userServiceClient.listByIds(anyList())).thenReturn(List.of(owner100, owner200));

        // Batch storage fetch
        when(fileServiceClient.listTeamStorageUsageByTeamIds(anyList()))
                .thenReturn(Map.of(1L, 1024L, 2L, 2048L, 3L, 512L));

        List<AdminTeamOverviewVO> result = adminTeamService.listTeams();

        assertEquals(3, result.size());

        // Verify batch call — single call for all owners
        verify(userServiceClient, times(1)).listByIds(argThat(ids ->
                ids.contains(100L) && ids.contains(200L) && ids.size() == 2));

        // Verify batch storage call — single call for all teams
        verify(fileServiceClient, times(1)).listTeamStorageUsageByTeamIds(argThat(ids ->
                ids.contains(1L) && ids.contains(2L) && ids.contains(3L)));

        // Verify populated values
        assertEquals("owner_a", result.get(0).getOwnerUsername());
        assertEquals(1024L, result.get(0).getUsedStorage());
        assertEquals("owner_b", result.get(1).getOwnerUsername());
        assertEquals(2048L, result.get(1).getUsedStorage());
        assertEquals("owner_a", result.get(2).getOwnerUsername());
        assertEquals(512L, result.get(2).getUsedStorage());
    }

    // ==================== listTeams — empty result ====================

    @Test
    void listTeams_withEmptyResult_shouldReturnEmptyList() {
        when(teamMapper.listAdminTeamOverviews()).thenReturn(Collections.emptyList());

        List<AdminTeamOverviewVO> result = adminTeamService.listTeams();

        assertTrue(result.isEmpty());
        // No batch calls should be made when there are no teams
        verifyNoInteractions(userServiceClient, fileServiceClient);
    }
}
