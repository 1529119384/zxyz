package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.entity.Team;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
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
@MockitoSettings(strictness = Strictness.LENIENT)
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
    private ProjectServiceClient projectServiceClient;

    @Mock
    private ImSystemNotificationClient imSystemNotificationClient;

    @Mock
    private EmailServiceClient emailServiceClient;

    private AdminTeamService adminTeamService;

    @BeforeEach
    void setUp() {
        adminTeamService = new AdminTeamService(
                teamMapper, teamQuotaMapper, userServiceClient,
                fileServiceClient, projectServiceClient, imSystemNotificationClient, emailServiceClient,
                null);
        // Self-injection for @Transactional proxy — in unit tests, point to the same instance
        adminTeamService.setSelf(adminTeamService);
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

    // ==================== updateTeamQuota — CV-3 项目配额总和校验 ====================

    @Test
    void updateTeamQuota_whenStorageLessThanProjectQuotaSum_shouldThrow() {
        // 团队存在且活跃
        Team team = new Team();
        team.setId(1L);
        team.setStatus(0);
        when(teamMapper.selectById(1L)).thenReturn(team);
        when(teamMapper.countOccupiedMembers(1L)).thenReturn(2);

        // 已用存储空间 500MB（可接受）
        when(fileServiceClient.sumActiveFileSize(null, 1L, 2, null)).thenReturn(500L * 1024 * 1024);

        // 项目配额总和 2GB > 团队配额 1GB → 应拒绝
        org.mockito.Mockito.doReturn((long) (2L * 1024 * 1024 * 1024)).when(projectServiceClient).sumProjectQuota(1L);

        UpdateTeamQuotaRequest request = new UpdateTeamQuotaRequest();
        request.setMemberLimit(10);
        request.setStorageLimit(1L * 1024 * 1024 * 1024); // 1GB

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminTeamService.updateTeamQuota(1L, request));
        assertTrue(ex.getMessage().contains("项目配额总和"));
        verify(teamQuotaMapper, never()).upsertQuota(any());
    }

    @Test
    void updateTeamQuota_whenStorageGreaterThanProjectQuotaSum_shouldSucceed() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Team A");
        team.setStatus(0);
        when(teamMapper.selectById(1L)).thenReturn(team);
        when(teamMapper.countOccupiedMembers(1L)).thenReturn(2);
        when(fileServiceClient.sumActiveFileSize(null, 1L, 2, null)).thenReturn(500L * 1024 * 1024);
        org.mockito.Mockito.doReturn((long) (2L * 1024 * 1024 * 1024)).when(projectServiceClient).sumProjectQuota(1L); // 项目总和 2GB

        UpdateTeamQuotaRequest request = new UpdateTeamQuotaRequest();
        request.setMemberLimit(10);
        request.setStorageLimit(2L * 1024 * 1024 * 1024); // 2GB > 500MB

        // listTeams 用于返回结果
        AdminTeamOverviewVO vo = new AdminTeamOverviewVO(
                1L, "Team A", null, null, null, 0, 0, 0L, null, null);
        when(teamMapper.listAdminTeamOverviews()).thenReturn(List.of(vo));

        when(teamQuotaMapper.upsertQuota(any())).thenReturn(1);

        var result = adminTeamService.updateTeamQuota(1L, request);
        assertNotNull(result);
        verify(teamQuotaMapper, times(1)).upsertQuota(any());
    }

    @Test
    void updateTeamQuota_whenNoProjects_shouldSucceed() {
        Team team = new Team();
        team.setId(1L);
        team.setName("Team A");
        team.setStatus(0);
        when(teamMapper.selectById(1L)).thenReturn(team);
        when(teamMapper.countOccupiedMembers(1L)).thenReturn(2);
        when(fileServiceClient.sumActiveFileSize(null, 1L, 2, null)).thenReturn(0L);
        org.mockito.Mockito.doReturn((long) 0).when(projectServiceClient).sumProjectQuota(1L); // 无项目

        UpdateTeamQuotaRequest request = new UpdateTeamQuotaRequest();
        request.setMemberLimit(10);
        request.setStorageLimit(1L * 1024 * 1024 * 1024);

        AdminTeamOverviewVO vo = new AdminTeamOverviewVO(
                1L, "Team A", null, null, null, 0, 0, 0L, null, null);
        when(teamMapper.listAdminTeamOverviews()).thenReturn(List.of(vo));
        when(teamQuotaMapper.upsertQuota(any())).thenReturn(1);

        var result = adminTeamService.updateTeamQuota(1L, request);
        assertNotNull(result);
        verify(teamQuotaMapper, times(1)).upsertQuota(any());
    }
}
