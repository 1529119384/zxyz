package uno.acloud.team.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uno.acloud.common.PageResult;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.dto.system.BroadcastSystemMessageRequest;
import uno.acloud.team.dto.system.ScheduledEmailBatchRequest;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.entity.Team;
import uno.acloud.team.infrastructure.client.EmailServiceClient;
import uno.acloud.team.infrastructure.client.FileServiceClient;
import uno.acloud.team.infrastructure.client.ImSystemNotificationClient;
import uno.acloud.team.infrastructure.client.ProjectServiceClient;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamMapper;
import uno.acloud.team.mapper.TeamQuotaMapper;
import uno.acloud.team.service.BroadcastBatchDispatcher;
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
                // 每批 2 条、间隔 0：让「分批」在用例里真的被走到（且不引入真实等待）
                new BroadcastBatchDispatcher(2, 0L),
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

        when(teamMapper.countAdminTeamOverviews()).thenReturn(3L);
        when(teamMapper.listAdminTeamOverviewsPaged(PageResult.DEFAULT_PAGE_SIZE, 0))
                .thenReturn(List.of(team1, team2, team3));

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

        PageResult<AdminTeamOverviewVO> page = adminTeamService.listTeams(null, null);
        List<AdminTeamOverviewVO> result = page.getList();

        assertEquals(3, result.size());
        // 信封必须回填归一化后的页码/页大小与真实总数，否则前端算不出页数
        assertEquals(1, page.getPage());
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, page.getPageSize());
        assertEquals(3L, page.getTotal());

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
        when(teamMapper.countAdminTeamOverviews()).thenReturn(0L);

        PageResult<AdminTeamOverviewVO> page = adminTeamService.listTeams(null, null);

        assertTrue(page.getList().isEmpty());
        assertEquals(0L, page.getTotal());
        // 一条数据都没有时不该去翻表，也不该发任何跨服务批量调用
        verify(teamMapper, never()).listAdminTeamOverviewsPaged(anyInt(), anyInt());
        verifyNoInteractions(userServiceClient, fileServiceClient);
    }

    @Test
    void listTeams_withPageBeyondRange_shouldReturnEmptyPageWithRealTotal() {
        // 页码越界：仍要如实回报总数，否则前端会以为总共 0 条而清空分页器
        when(teamMapper.countAdminTeamOverviews()).thenReturn(50L);
        when(teamMapper.listAdminTeamOverviewsPaged(anyInt(), anyInt())).thenReturn(Collections.emptyList());

        PageResult<AdminTeamOverviewVO> page = adminTeamService.listTeams(9, 20);

        assertTrue(page.getList().isEmpty());
        assertEquals(50L, page.getTotal());
        assertEquals(9, page.getPage());
        assertEquals(20, page.getPageSize());
        verifyNoInteractions(userServiceClient, fileServiceClient);
    }

    @Test
    void listTeams_shouldClampPageSizeAndComputeOffsetFromPage() {
        // pageSize 超上限必须被钳到 200，否则传入极大值等于把接口恢复成全表查询
        when(teamMapper.countAdminTeamOverviews()).thenReturn(1000L);
        when(teamMapper.listAdminTeamOverviewsPaged(PageResult.MAX_PAGE_SIZE,
                PageResult.offsetOf(2, PageResult.MAX_PAGE_SIZE))).thenReturn(Collections.emptyList());

        PageResult<AdminTeamOverviewVO> page = adminTeamService.listTeams(2, 99999);

        assertEquals(PageResult.MAX_PAGE_SIZE, page.getPageSize());
        assertEquals(2, page.getPage());
        verify(teamMapper).listAdminTeamOverviewsPaged(PageResult.MAX_PAGE_SIZE,
                PageResult.offsetOf(2, PageResult.MAX_PAGE_SIZE));
    }

    // ==================== 广播 — L10 分批派发 ====================

    private static BroadcastSystemMessageRequest broadcastRequest() {
        BroadcastSystemMessageRequest request = new BroadcastSystemMessageRequest();
        request.setTitle("系统维护通知");
        request.setContent("今晚 23:00 起维护");
        return request;
    }

    @Test
    void broadcastSystemMessage_shouldDispatchNotificationsInBatchesInsteadOfOneGiantRequest() {
        when(userServiceClient.getAllUserIds()).thenReturn(List.of(1L, 2L, 3L, 4L, 5L));
        when(userServiceClient.getVerifiedEmails()).thenReturn(List.of());

        adminTeamService.broadcastSystemMessage(broadcastRequest());

        // 5 个用户 / 每批 2 ⇒ 3 批。核心契约是「不存在任何一批装下全部 5 条」——
        // 一旦退回单次全量，L10 要修的请求体过大 / 单个长事务就原样回来了。
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(imSystemNotificationClient, times(3))
                .sendBatch(captor.capture(), any(), any(), any(), any(), any(), any());
        assertEquals(List.of(List.of(1L, 2L), List.of(3L, 4L), List.of(5L)), captor.getAllValues());
    }

    @Test
    void broadcastSystemMessage_shouldDispatchEmailsInBatchesToo() {
        when(userServiceClient.getAllUserIds()).thenReturn(List.of(1L));
        when(userServiceClient.getVerifiedEmails())
                .thenReturn(List.of("a@x.com", "b@x.com", "c@x.com"));

        adminTeamService.broadcastSystemMessage(broadcastRequest());

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailServiceClient, times(2))
                .sendBatchByTemplate(captor.capture(), any(), any(), any(), any());
        assertEquals(List.of(List.of("a@x.com", "b@x.com"), List.of("c@x.com")), captor.getAllValues());
    }

    @Test
    void broadcastSystemMessage_shouldKeepTryingRemainingEmailBatchesWhenOneBatchFails() {
        when(userServiceClient.getAllUserIds()).thenReturn(List.of(1L));
        when(userServiceClient.getVerifiedEmails())
                .thenReturn(List.of("a@x.com", "b@x.com", "c@x.com"));
        doThrow(new RuntimeException("smtp down")).when(emailServiceClient)
                .sendBatchByTemplate(anyList(), any(), any(), any(), any());

        adminTeamService.broadcastSystemMessage(broadcastRequest());

        // 批内失败不得打断后续批次：站内消息已经发出去了，剩下的收件人仍应尝试投递
        verify(emailServiceClient, times(2)).sendBatchByTemplate(anyList(), any(), any(), any(), any());
    }

    @Test
    void scheduleSystemEmailBatch_shouldDispatchInBatches() {
        when(userServiceClient.getVerifiedEmails())
                .thenReturn(List.of("a@x.com", "b@x.com", "c@x.com"));
        ScheduledEmailBatchRequest request = new ScheduledEmailBatchRequest();
        request.setSubject("周报");
        request.setContentHtml("<p>内容</p>");
        request.setScheduledTime(LocalDateTime.now().plusDays(1));

        adminTeamService.scheduleSystemEmailBatch(request);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(emailServiceClient, times(2)).scheduleBatch(captor.capture(), eq("周报"), eq("<p>内容</p>"),
                any(), eq("ADMIN_SCHEDULED_EMAIL"), isNull());
        assertEquals(2, captor.getAllValues().size());
    }

    @Test
    void broadcastSystemMessage_shouldNotCallDownstreamWhenThereAreNoUsers() {
        when(userServiceClient.getAllUserIds()).thenReturn(List.of());
        when(userServiceClient.getVerifiedEmails()).thenReturn(List.of());

        adminTeamService.broadcastSystemMessage(broadcastRequest());

        verifyNoInteractions(imSystemNotificationClient, emailServiceClient);
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

        // 单团队查询用于返回结果（不再走全量 listTeams）
        AdminTeamOverviewVO vo = new AdminTeamOverviewVO(
                1L, "Team A", null, null, null, 0, 0, 0L, null, null);
        when(teamMapper.getAdminTeamOverview(1L)).thenReturn(vo);

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
        when(teamMapper.getAdminTeamOverview(1L)).thenReturn(vo);
        when(teamQuotaMapper.upsertQuota(any())).thenReturn(1);

        var result = adminTeamService.updateTeamQuota(1L, request);
        assertNotNull(result);
        verify(teamQuotaMapper, times(1)).upsertQuota(any());
    }

    /**
     * 回归守卫：改单个团队的配额必须走「单条查询」。
     *
     * <p>原实现用 {@code listTeams()} 拉全量再过滤，本用例把「别再退回去」钉死：
     * 一旦有人改回全量路径，这里立刻会红。</p>
     */
    @Test
    void updateTeamQuota_shouldQuerySingleTeamInsteadOfFullList() {
        Team team = new Team();
        team.setId(7L);
        team.setName("Team G");
        team.setStatus(0);
        when(teamMapper.selectById(7L)).thenReturn(team);
        when(teamMapper.countOccupiedMembers(7L)).thenReturn(1);
        when(fileServiceClient.sumActiveFileSize(null, 7L, 2, null)).thenReturn(0L);
        org.mockito.Mockito.doReturn(0L).when(projectServiceClient).sumProjectQuota(7L);
        when(teamQuotaMapper.upsertQuota(any())).thenReturn(1);

        // owner 用户名与已用存储都必须在单条路径上补齐，不能因为"不再走列表"而丢字段
        AdminTeamOverviewVO vo = new AdminTeamOverviewVO(
                7L, "Team G", null, 900L, null, 1, 10, 1024L, null, null);
        when(teamMapper.getAdminTeamOverview(7L)).thenReturn(vo);
        UserInfoDTO owner = new UserInfoDTO();
        owner.setId(900L);
        owner.setUsername("owner-g");
        when(userServiceClient.listByIds(List.of(900L))).thenReturn(List.of(owner));
        when(fileServiceClient.listTeamStorageUsageByTeamIds(List.of(7L))).thenReturn(Map.of(7L, 2048L));

        UpdateTeamQuotaRequest request = new UpdateTeamQuotaRequest();
        request.setMemberLimit(10);
        request.setStorageLimit(2048L);

        AdminTeamOverviewVO result = adminTeamService.updateTeamQuota(7L, request);

        assertEquals("owner-g", result.getOwnerUsername());
        assertEquals(2048L, result.getUsedStorage().longValue());
        verify(teamMapper).getAdminTeamOverview(7L);
        // 列表查询绝不能被调用（只允许单团队定点查询）
        verify(teamMapper, never()).listAdminTeamOverviewsPaged(anyInt(), anyInt());
        // 跨服务调用只针对这一个团队，而不是全部团队
        verify(userServiceClient).listByIds(List.of(900L));
        verify(fileServiceClient).listTeamStorageUsageByTeamIds(List.of(7L));
    }
}
