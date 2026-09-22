package uno.acloud.project.service.impl;

import uno.acloud.client.UserQueryClient;
import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.stereotype.Component;
import uno.acloud.common.FileSpaceType;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.project.entity.ProjectMember;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.dto.UserInfoDTO;
import uno.acloud.project.mapper.ProjectEntityMapper;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mapper.ProjectQuotaMapper;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 项目实体 → VO 的装配器。
 *
 * <p>{@link ProjectVO} 上有 4 个<b>计算字段</b>（{@code storageLimit} / {@code usedStorage} /
 * {@code accessible} / {@code manageable}），MapStruct 不负责它们，只能在此补充。
 * 因此本类有两种装配方式，<b>计算字段的语义必须完全一致</b>：</p>
 * <ul>
 *   <li>{@link #toProjectVO} —— 单个项目。用于创建 / 归档 / 改负责人 / 配额 / 审批等
 *       天然以「一个项目」为单位的路径。</li>
 *   <li>{@link #toProjectVOList} —— 同一团队的一批项目。用于列表页。</li>
 * </ul>
 *
 * <h2>为什么必须有两个入口（列表页的 N+1）</h2>
 * <p>计算字段的来源里有两个是<b>按项目</b>取数的，其中 {@code usedStorage} 还是一次
 * <b>远程 HTTP</b> 调用（{@code file-service} 的 {@code /api/internal/storage/sum-active}），
 * {@code manageable} 所依赖的权限校验是另一次远程调用（{@code team-service} 的
 * {@code /api/internal/permissions/has}）。若列表页逐项目调 {@link #toProjectVO}，
 * P 个项目就是 {@code 2P} 次远程调用 + {@code 2P} 次 DB 查询。</p>
 * <p>更关键的是：那些调用里<b>有一部分与项目无关</b>（见下），逐项目调用会把它们重复 P 遍。
 * {@link #toProjectVOList} 把它们压成固定次数。调用点见
 * {@code ProjectCatalogService#listVisibleProjects}。</p>
 *
 * <h2>批量路径的「可省略调用」清单（改这个方法前先读）</h2>
 * <table border="1">
 *   <caption>各字段在批量路径下的取数方式</caption>
 *   <tr><th>字段</th><th>单个项目</th><th>批量（P 个项目）</th><th>为什么可以这样</th></tr>
 *   <tr><td>{@code storageLimit}</td><td>1 查询</td><td>1 查询</td>
 *       <td>按团队一次取回，按 projectId 建索引</td></tr>
 *   <tr><td>{@code usedStorage}</td><td>1 远程</td><td>1 远程</td>
 *       <td>file-service 的 {@code project-usage-list} 按 projectIds 批量聚合</td></tr>
 *   <tr><td>{@code accessible}</td><td>1 查询</td><td>1 查询</td>
 *       <td>按 (teamId, userId) 一次取回「已加入的项目 id」集合</td></tr>
 *   <tr><td>{@code manageable}</td><td>1 远程</td><td><b>0 次</b></td>
 *       <td>团队权限与项目无关 ⇒ 循环外算一次，循环内只做「是否负责人」的本地比较</td></tr>
 * </table>
 * <p>⚠️ 最后一行是最容易被改回去的一处：把 {@code hasPermission} 挪回循环内不会报错、
 * 结果也「看起来对」，只是又变回 P 次远程调用。请勿回退。</p>
 */
@Component
public class ProjectViewAssembler {

    private final ProjectQuotaMapper projectQuotaMapper;
    private final FileServiceClient fileServiceClient;
    private final ProjectMapper projectMapper;
    private final UserQueryClient userQueryClient;
    private final TeamFileAccessPort teamFileAccessService;
    private final ProjectEntityMapper projectEntityMapper;

    public ProjectViewAssembler(ProjectQuotaMapper projectQuotaMapper,
                                FileServiceClient fileServiceClient,
                                ProjectMapper projectMapper,
                                UserQueryClient userQueryClient,
                                TeamFileAccessPort teamFileAccessService,
                                ProjectEntityMapper projectEntityMapper) {
        this.projectQuotaMapper = projectQuotaMapper;
        this.fileServiceClient = fileServiceClient;
        this.projectMapper = projectMapper;
        this.userQueryClient = userQueryClient;
        this.teamFileAccessService = teamFileAccessService;
        this.projectEntityMapper = projectEntityMapper;
    }

    public ProjectVO toProjectVO(Project project, Long userId) {
        // MapStruct 处理基础字段映射，计算字段在此补充
        ProjectVO vo = projectEntityMapper.toProjectVO(project);
        ProjectQuota quota = projectQuotaMapper.getByProjectId(project.getId());
        long usedStorage = fileServiceClient.sumActiveFileSize(
                userId, project.getTeamId(), FileSpaceType.PROJECT, project.getId());
        boolean member = projectMapper.countMember(project.getId(), userId) > 0;
        boolean canManageTeam = teamFileAccessService.hasPermission(
                userId, project.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        applyComputedFields(vo, project, userId, quota == null ? null : quota.getStorageLimit(),
                usedStorage, member, canManageTeam);
        return vo;
    }

    /**
     * 批量装配同一团队的一批项目（列表页专用，见类注释）。
     *
     * <p>与逐项目调用 {@link #toProjectVO} 相比，远程调用从 {@code 2P} 降到 {@code 1}，
     * DB 查询从 {@code 2P} 降到 {@code 2}；计算字段的取值语义不变。</p>
     *
     * <p><b>前置条件</b>（违反即抛 {@link IllegalArgumentException}，不做静默降级）：
     * 传入的每个 project 的 {@code teamId} 相同且非 null。这是因为「配额」与「是否已加入」
     * 两个批量查询是<b>按团队</b>取数的 —— 若混入别的团队的项目，它不会报错，
     * 只会拿到 {@code storageLimit = null}、{@code usedStorage = 0}、{@code accessible = false}
     * 这一组「看起来正常但全错」的值。宁可响亮失败。</p>
     *
     * @param projects 同一团队的项目列表（通常直接来自 {@code ProjectMapper#listVisibleProjects}）
     * @param userId   当前用户 id
     * @return 与入参<b>同序</b>的 VO 列表
     */
    public List<ProjectVO> toProjectVOList(List<Project> projects, Long userId) {
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        Long teamId = requireSingleTeamId(projects);

        List<ProjectVO> vos = projectEntityMapper.toProjectVOList(projects);

        // ① 配额：一次取回整团队（按 projectId 建索引；limit 为 null 表示不限制，与单项目路径一致）
        Map<Long, Long> storageLimits = projectQuotaMapper.listByVisibleTeamProjects(teamId).stream()
                .filter(quota -> quota.getStorageLimit() != null)
                .collect(Collectors.toMap(ProjectQuota::getProjectId, ProjectQuota::getStorageLimit,
                        (existing, ignored) -> existing));

        // ② 用量：一次远程批量；未在返回集合里的项目按 0 处理（等价于单项目路径的 COALESCE(...,0)）
        Map<Long, Long> usedStorageByProject = fileServiceClient.listProjectStorageUsageByProjectIds(
                projects.stream().map(Project::getId).toList());

        // ③ 是否已加入：一次取回「我加入的项目 id」，循环内只做集合判定
        Set<Long> memberProjectIds = Set.copyOf(projectMapper.listMemberProjectIds(teamId, userId));

        // ④ 团队级管理权：与具体项目无关 ⇒ 循环外只算一次
        boolean canManageTeam = teamFileAccessService.hasPermission(
                userId, teamId, TeamPermissionCodes.TEAM_PROJECT_MANAGE);

        for (int i = 0; i < projects.size(); i++) {
            Project project = projects.get(i);
            Long projectId = project.getId();
            applyComputedFields(vos.get(i), project, userId,
                    storageLimits.get(projectId),
                    usedStorageByProject.getOrDefault(projectId, 0L),
                    memberProjectIds.contains(projectId),
                    canManageTeam);
        }
        return vos;
    }

    public ProjectCreateRequestVO toCreateRequestVO(ProjectCreateRequest request) {
        return toCreateRequestVO(request, loadCreateRequestUsers(List.of(request)));
    }

    public List<ProjectCreateRequestVO> toCreateRequestVOList(List<ProjectCreateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        Map<Long, UserInfoDTO> users = loadCreateRequestUsers(requests);
        return requests.stream()
                .map(request -> toCreateRequestVO(request, users))
                .toList();
    }

    /**
     * 将项目成员实体转为 VO，展示字段（username、name、avatar）已从 ProjectMember 实体移除，需通过 userMap 从 User 表获取。
     */
    public ProjectMemberVO toMemberVO(ProjectMember member, Map<Long, UserInfoDTO> userMap) {
        return projectEntityMapper.toMemberVO(member, userMap);
    }

    /**
     * 批量将项目成员列表转为 VO，内部会一次性查询所有关联的 User 信息，避免 N+1 查询。
     */
    public List<ProjectMemberVO> toMemberVOList(List<ProjectMember> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = members.stream().map(ProjectMember::getUserId).distinct().toList();
        Map<Long, UserInfoDTO> userMap = userQueryClient.listByIds(userIds).stream()
                .collect(Collectors.toMap(UserInfoDTO::getId, Function.identity()));
        return members.stream().map(m -> toMemberVO(m, userMap)).toList();
    }

    /**
     * 计算字段的<b>唯一</b>写入点。
     *
     * <p>{@link #toProjectVO} 与 {@link #toProjectVOList} 都必须经由此方法 ——
     * 两个入口各写一份赋值是「加了字段/改了口径只改一处」的经典温床，
     * 且症状是「列表页与详情页显示不一致」，很难归因。</p>
     *
     * @param canManageTeam 用户是否具备该团队的项目管理权限（与具体项目无关，故由调用方算好后传入）
     */
    private void applyComputedFields(ProjectVO vo,
                                     Project project,
                                     Long userId,
                                     Long storageLimit,
                                     long usedStorage,
                                     boolean member,
                                     boolean canManageTeam) {
        vo.setStorageLimit(storageLimit);
        vo.setUsedStorage(usedStorage);
        vo.setAccessible(member);
        vo.setManageable(project.getLeaderUserId().equals(userId) || canManageTeam);
    }

    /**
     * 校验「一批项目同属一个团队」并返回该 teamId。
     * <p>违反时抛 {@link IllegalArgumentException}：批量查询是按团队取数的，
     * 混团队会得到静默错误的结果（见 {@link #toProjectVOList} 的前置条件）。</p>
     */
    private Long requireSingleTeamId(List<Project> projects) {
        Set<Long> teamIds = projects.stream().map(Project::getTeamId).collect(Collectors.toSet());
        if (teamIds.size() != 1 || teamIds.contains(null)) {
            throw new IllegalArgumentException(
                    "toProjectVOList 只接受同一团队的项目（配额与成员关系均为按团队取数），实际传入了 "
                            + (teamIds.contains(null) ? "含 teamId=null 的项目" : teamIds.size() + " 个团队的混合列表："
                            + teamIds));
        }
        return teamIds.iterator().next();
    }

    private ProjectCreateRequestVO toCreateRequestVO(ProjectCreateRequest request, Map<Long, UserInfoDTO> users) {
        return projectEntityMapper.toCreateRequestVO(request, users);
    }

    private Map<Long, UserInfoDTO> loadCreateRequestUsers(List<ProjectCreateRequest> requests) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (ProjectCreateRequest request : requests) {
            if (request.getRequesterUserId() != null) {
                userIds.add(request.getRequesterUserId());
            }
            if (request.getLeaderUserId() != null) {
                userIds.add(request.getLeaderUserId());
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        // 批量加载展示用户，避免待审批列表按申请逐条查询用户表。
        return userQueryClient.listByIds(List.copyOf(userIds)).stream()
                .collect(Collectors.toMap(UserInfoDTO::getId, Function.identity(), (existing, replacement) -> existing));
    }

}
