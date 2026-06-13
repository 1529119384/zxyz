package uno.acloud.project.service.impl;

import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.service.ProjectAccessGuardPort;
import uno.acloud.project.service.ProjectCatalogPort;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

@Service
public class ProjectCatalogService implements ProjectCatalogPort {

    private final ProjectMapper projectMapper;
    private final TeamFileAccessPort teamFileAccessService;
    private final ProjectAccessGuardPort projectAccessGuard;
    private final ProjectCreationCommand projectCreationCommand;
    private final ProjectViewAssembler viewAssembler;
    private final ProjectCollaborationCoordinator collaborationService;

    public ProjectCatalogService(ProjectMapper projectMapper,
                                 TeamFileAccessPort teamFileAccessService,
                                 ProjectAccessGuardPort projectAccessGuard,
                                 ProjectCreationCommand projectCreationCommand,
                                 ProjectViewAssembler viewAssembler,
                                 ProjectCollaborationCoordinator collaborationService) {
        this.projectMapper = projectMapper;
        this.teamFileAccessService = teamFileAccessService;
        this.projectAccessGuard = projectAccessGuard;
        this.projectCreationCommand = projectCreationCommand;
        this.viewAssembler = viewAssembler;
        this.collaborationService = collaborationService;
    }

    @Override
    public List<ProjectVO> listVisibleProjects(Long teamId, Long userId) {
        teamFileAccessService.requireTeamMember(teamId, userId);
        return projectMapper.listVisibleProjects(teamId, userId).stream()
                .map(project -> viewAssembler.toProjectVO(project, userId))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProjectVO createProject(Long teamId, CreateProjectRequest request, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        return projectCreationCommand.createProject(teamId, request, operatorUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProjectVO archiveProject(Long projectId, Long operatorUserId) {
        Project project = projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId);
        projectMapper.archiveProject(projectId);
        collaborationService.archiveProjectConversation(projectId);
        project.setStatus(1);
        return viewAssembler.toProjectVO(project, operatorUserId);
    }
}
