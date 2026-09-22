package uno.acloud.project.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import uno.acloud.client.UserQueryClient;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.project.mapper.ProjectEntityMapper;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.service.TeamFileAccessPort;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectViewAssemblerTest {

    private static final Long TEAM_ID = 7L;
    private static final Long VIEWER_ID = 5L;

    @Mock
    private ProjectQuotaMapper projectQuotaMapper;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserQueryClient userQueryClient;

    @Mock
    private TeamFileAccessPort teamFileAccessService;

    @Spy
    private ProjectEntityMapper projectEntityMapper = Mappers.getMapper(ProjectEntityMapper.class);

    private ProjectViewAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ProjectViewAssembler(
                projectQuotaMapper,
                fileServiceClient,
                projectMapper,
                userQueryClient,
                teamFileAccessService,
                projectEntityMapper
        );
    }

    // ==================================================================================
    // 批量装配（列表页）—— C-5：N+1 修复
    // ==================================================================================

    /**
     * 三个项目的一次批量装配：核对每个计算字段的取值。
     */
    @Test
    void toProjectVOListShouldAssembleComputedFieldsFromBatchResults() {
        Project first = project(100L, 1L);
        Project second = project(101L, 2L);
        Project third = project(102L, 3L);

        when(projectQuotaMapper.listByVisibleTeamProjects(TEAM_ID))
                .thenReturn(List.of(quota(100L, 1024L), quota(101L, 2048L)));
        when(fileServiceClient.listProjectStorageUsageByProjectIds(List.of(100L, 101L, 102L)))
                .thenReturn(Map.of(100L, 11L, 102L, 33L));
        when(projectMapper.listMemberProjectIds(TEAM_ID, VIEWER_ID)).thenReturn(List.of(101L));
        when(teamFileAccessService.hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(false);

        List<ProjectVO> result = assembler.toProjectVOList(List.of(first, second, third), VIEWER_ID);

        assertEquals(3, result.size());
        // 顺序必须与入参一致：列表页靠它保持 SQL 的排序结果
        assertEquals(100L, result.get(0).getId().longValue());
        assertEquals(101L, result.get(1).getId().longValue());
        assertEquals(102L, result.get(2).getId().longValue());

        // 配额：有行则填，无行则 null（与 getByProjectId 返回 null 等价）
        assertEquals(1024L, result.get(0).getStorageLimit().longValue());
        assertEquals(2048L, result.get(1).getStorageLimit().longValue());
        assertNull(result.get(2).getStorageLimit(), "没有配额行的项目应为 null（不限制），而不是 0");

        // 用量：批量结果里缺席的项目按 0 —— 等价于单值版 COALESCE(SUM(...), 0) 对空项目返回 0
        assertEquals(11L, result.get(0).getUsedStorage().longValue());
        assertEquals(0L, result.get(1).getUsedStorage().longValue(), "缺席即 0");
        assertEquals(33L, result.get(2).getUsedStorage().longValue());

        // 可访问：只有 101 在「我加入的项目」集合里
        assertEquals(Boolean.FALSE, result.get(0).getAccessible());
        assertEquals(Boolean.TRUE, result.get(1).getAccessible());
        assertEquals(Boolean.FALSE, result.get(2).getAccessible());

        // 可管理：三个项目都不是本人负责，且团队权限为 false
        assertEquals(Boolean.FALSE, result.get(0).getManageable());
    }

    /**
     * <b>N+1 回归门禁</b>：列表页的按项目取数必须各只发生一次。
     *
     * <p>刻意独立成一个用例，只断言<b>调用次数</b>、不断言取值：
     * 取值断言与次数断言混在一起时，一旦有人把批量路径改回循环，
     * 先失败的往往是取值断言（例如未 stub 的单值调用返回 0），
     * 报错信息指向「值不对」而不是「又变回 N+1」—— 定位方向被带偏。
     * 分开之后，本用例失败即等价于「有人把批量路径改回了逐项目调用」。</p>
     */
    @Test
    void toProjectVOListMustNotFallBackToPerProjectLookups() {
        List<Project> projects = List.of(project(100L, 1L), project(101L, 2L), project(102L, 3L));

        when(projectQuotaMapper.listByVisibleTeamProjects(TEAM_ID)).thenReturn(List.of());
        when(fileServiceClient.listProjectStorageUsageByProjectIds(List.of(100L, 101L, 102L)))
                .thenReturn(Map.<Long, Long>of());
        when(projectMapper.listMemberProjectIds(TEAM_ID, VIEWER_ID)).thenReturn(List.of());
        when(teamFileAccessService.hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(false);

        assembler.toProjectVOList(projects, VIEWER_ID);

        // 按项目取数：3 个项目也只能各 1 次
        verify(projectQuotaMapper, times(1)).listByVisibleTeamProjects(TEAM_ID);
        verify(fileServiceClient, times(1)).listProjectStorageUsageByProjectIds(List.of(100L, 101L, 102L));
        verify(projectMapper, times(1)).listMemberProjectIds(TEAM_ID, VIEWER_ID);

        // 与项目无关的权限校验必须被提到循环外：3 个项目也只问 1 次
        verify(teamFileAccessService, times(1))
                .hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE);

        // 逐项目版本的三个调用一个都不许出现
        // （它们的非空性由 toProjectVOShouldStillUsePerProjectLookups 保证：
        //   那个用例证明 mock 确实会记录这三个调用，故这里的 never() 不是空断言）
        verify(projectQuotaMapper, never()).getByProjectId(any());
        verify(fileServiceClient, never()).sumActiveFileSize(any(), any(), any(), any());
        verify(projectMapper, never()).countMember(any(), any());
    }

    @Test
    void toProjectVOListShouldMarkLeaderManageableWithoutTeamPermission() {
        Project ledByViewer = project(100L, VIEWER_ID);

        when(projectQuotaMapper.listByVisibleTeamProjects(TEAM_ID)).thenReturn(List.of());
        when(fileServiceClient.listProjectStorageUsageByProjectIds(List.of(100L)))
                .thenReturn(Map.<Long, Long>of());
        when(projectMapper.listMemberProjectIds(TEAM_ID, VIEWER_ID)).thenReturn(List.of());
        when(teamFileAccessService.hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(false);

        List<ProjectVO> result = assembler.toProjectVOList(List.of(ledByViewer), VIEWER_ID);

        // 负责人身份与团队权限是「或」关系：没有团队管理权，作为负责人仍应可管理
        assertEquals(Boolean.TRUE, result.get(0).getManageable());
    }

    @Test
    void toProjectVOListShouldGrantManageableToEveryProjectWhenTeamPermissionHeld() {
        Project other = project(100L, 1L);

        when(projectQuotaMapper.listByVisibleTeamProjects(TEAM_ID)).thenReturn(List.of());
        when(fileServiceClient.listProjectStorageUsageByProjectIds(List.of(100L)))
                .thenReturn(Map.<Long, Long>of());
        when(projectMapper.listMemberProjectIds(TEAM_ID, VIEWER_ID)).thenReturn(List.of());
        when(teamFileAccessService.hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(true);

        List<ProjectVO> result = assembler.toProjectVOList(List.of(other), VIEWER_ID);

        // 团队级权限一次判定即对全批生效 —— 这正是「提到循环外」的语义前提
        assertEquals(Boolean.TRUE, result.get(0).getManageable());
    }

    @Test
    void toProjectVOListShouldSkipAllQueriesWhenEmpty() {
        assertTrue(assembler.toProjectVOList(List.of(), VIEWER_ID).isEmpty());

        verifyNoInteractions(projectQuotaMapper, fileServiceClient, projectMapper, teamFileAccessService);
    }

    /**
     * 混团队必须响亮失败。
     *
     * <p>批量查询是「按团队」取数的：若混入别的团队的项目，它不会报错，
     * 只会拿到 {@code storageLimit = null}、{@code usedStorage = 0}、{@code accessible = false}
     * 这一组「看起来正常但全错」的值。故此处断言它抛异常而非静默降级。</p>
     */
    @Test
    void toProjectVOListShouldRejectProjectsFromDifferentTeams() {
        Project inTeam7 = project(100L, 1L, TEAM_ID);
        Project inTeam8 = project(101L, 2L, 8L);

        assertThrows(IllegalArgumentException.class,
                () -> assembler.toProjectVOList(List.of(inTeam7, inTeam8), VIEWER_ID));
    }

    @Test
    void toProjectVOListShouldRejectProjectsWithoutTeam() {
        Project withoutTeam = project(100L, 1L, null);

        assertThrows(IllegalArgumentException.class,
                () -> assembler.toProjectVOList(List.of(withoutTeam), VIEWER_ID));
    }

    // ==================================================================================
    // 单项目装配 —— 同时用于证明上面 never() 断言非空
    // ==================================================================================

    /**
     * 单项目路径<b>仍然</b>逐项取数：它天生只有一个项目，没有 N+1 可言，
     * 保持原样能避免为「创建 / 归档 / 改负责人」等路径引入批量查询的额外开销。
     *
     * <p>更重要的作用是作为「mock 会记录这些调用」的证据 ——
     * 若没有本用例，批量用例里的 {@code never()} 断言即使被写成空断言也不会有人发现。</p>
     */
    @Test
    void toProjectVOShouldStillUsePerProjectLookups() {
        Project project = project(100L, 1L);

        when(projectQuotaMapper.getByProjectId(100L)).thenReturn(quota(100L, 1024L));
        when(fileServiceClient.sumActiveFileSize(VIEWER_ID, TEAM_ID, FileSpaceType.PROJECT, 100L))
                .thenReturn(9L);
        when(projectMapper.countMember(100L, VIEWER_ID)).thenReturn(1);
        when(teamFileAccessService.hasPermission(VIEWER_ID, TEAM_ID, TeamPermissionCodes.TEAM_PROJECT_MANAGE))
                .thenReturn(false);

        ProjectVO vo = assembler.toProjectVO(project, VIEWER_ID);

        assertEquals(1024L, vo.getStorageLimit().longValue());
        assertEquals(9L, vo.getUsedStorage().longValue());
        assertEquals(Boolean.TRUE, vo.getAccessible());
        assertEquals(Boolean.FALSE, vo.getManageable());

        verify(projectMapper).countMember(100L, VIEWER_ID);
        verify(fileServiceClient).sumActiveFileSize(VIEWER_ID, TEAM_ID, FileSpaceType.PROJECT, 100L);
        verify(projectQuotaMapper).getByProjectId(100L);
    }

    @Test
    void toCreateRequestVOListShouldResolveDisplayNamesInBatch() {
        ProjectCreateRequest first = createRequest(10L, 1L, 2L);
        ProjectCreateRequest second = createRequest(11L, 3L, 2L);
        when(userQueryClient.listByIds(List.of(1L, 2L, 3L))).thenReturn(List.of(
                user(1, "requester_user", "张三"),
                user(2, "leader_user", ""),
                user(3, "", "")
        ));

        List<ProjectCreateRequestVO> result = assembler.toCreateRequestVOList(List.of(first, second));

        assertEquals("张三", result.get(0).getRequesterName());
        assertEquals("leader_user", result.get(0).getLeaderName());
        assertEquals("用户 3", result.get(1).getRequesterName());
        assertEquals("leader_user", result.get(1).getLeaderName());
        verify(userQueryClient).listByIds(List.of(1L, 2L, 3L));
    }

    @Test
    void toCreateRequestVOListShouldSkipUserQueryWhenEmpty() {
        assertTrue(assembler.toCreateRequestVOList(List.of()).isEmpty());
    }

    private Project project(Long id, Long leaderUserId) {
        return project(id, leaderUserId, TEAM_ID);
    }

    private Project project(Long id, Long leaderUserId, Long teamId) {
        Project project = new Project();
        project.setId(id);
        project.setTeamId(teamId);
        project.setName("项目" + id);
        project.setLeaderUserId(leaderUserId);
        project.setStatus(0);
        return project;
    }

    private ProjectQuota quota(Long projectId, Long storageLimit) {
        ProjectQuota quota = new ProjectQuota();
        quota.setId(projectId * 10);
        quota.setProjectId(projectId);
        quota.setStorageLimit(storageLimit);
        return quota;
    }

    private ProjectCreateRequest createRequest(Long id, Long requesterUserId, Long leaderUserId) {
        ProjectCreateRequest createRequest = new ProjectCreateRequest();
        createRequest.setId(id);
        createRequest.setTeamId(100L);
        createRequest.setRequesterUserId(requesterUserId);
        createRequest.setProjectName("项目" + id);
        createRequest.setDescription("说明" + id);
        createRequest.setLeaderUserId(leaderUserId);
        createRequest.setStatus(0);
        return createRequest;
    }

    private UserInfoDTO user(long id, String username, String name) {
        UserInfoDTO dto = new UserInfoDTO();
        dto.setId(id);
        dto.setUsername(username);
        dto.setName(name);
        return dto;
    }
}
