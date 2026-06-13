package uno.acloud.project.service.impl;

import uno.acloud.client.UserQueryClient;
import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.stereotype.Component;
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
        long usedStorage = fileServiceClient.sumActiveFileSize(userId, project.getTeamId(), 3, project.getId());
        boolean member = projectMapper.countMember(project.getId(), userId) > 0;
        boolean manageable = project.getLeaderUserId().equals(userId)
                || teamFileAccessService.hasPermission(userId, project.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        vo.setStorageLimit(quota == null ? null : quota.getStorageLimit());
        vo.setUsedStorage(usedStorage);
        vo.setAccessible(member);
        vo.setManageable(manageable);
        return vo;
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
