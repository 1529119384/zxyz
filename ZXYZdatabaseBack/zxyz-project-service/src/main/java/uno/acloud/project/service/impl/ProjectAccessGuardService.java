package uno.acloud.project.service.impl;

import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.entity.Project;
import uno.acloud.exception.BusinessException;
import uno.acloud.exception.ForbiddenException;
import uno.acloud.exception.NotFoundException;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.service.ProjectAccessGuardPort;

@Service
public class ProjectAccessGuardService implements ProjectAccessGuardPort {

    private final ProjectMapper projectMapper;
    private final TeamFileAccessPort teamFileAccessService;

    public ProjectAccessGuardService(ProjectMapper projectMapper, TeamFileAccessPort teamFileAccessService) {
        this.projectMapper = projectMapper;
        this.teamFileAccessService = teamFileAccessService;
    }

    @Override
    public Project requireProjectAccess(Long projectId, Long userId) {
        Project project = requireProject(projectId);
        if (projectMapper.countMember(projectId, userId) > 0) {
            return project;
        }
        if (teamFileAccessService.hasPermission(userId, project.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE)) {
            return project;
        }
        throw new ForbiddenException(ErrorCode.NO_PERMISSION, "无权访问该项目组");
    }

    @Override
    public Project requireProjectFileAccess(Long projectId, Long userId) {
        Project project = requireProject(projectId);
        if (projectMapper.countMember(projectId, userId) <= 0) {
            throw new ForbiddenException(ErrorCode.NO_PERMISSION, "只有项目成员可以访问项目文件");
        }
        return project;
    }

    @Override
    public Project requireProjectManageAccess(Long projectId, Long userId) {
        Project project = requireProject(projectId);
        if (project.getLeaderUserId().equals(userId)) {
            return project;
        }
        teamFileAccessService.check(userId, project.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        return project;
    }

    private Project requireProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new NotFoundException(ErrorCode.PROJECT_NOT_FOUND, "项目组不存在");
        }
        if (Integer.valueOf(1).equals(project.getStatus())) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "项目组已归档");
        }
        return project;
    }
}
