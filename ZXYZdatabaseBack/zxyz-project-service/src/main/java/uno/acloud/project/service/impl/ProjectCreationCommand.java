package uno.acloud.project.service.impl;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.project.common.ProjectRoleCodes;
import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.vo.project.ProjectVO;

import java.time.LocalDateTime;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Component
public class ProjectCreationCommand {

    private final ProjectMapper projectMapper;
    private final ProjectCommandSupport commandSupport;
    private final ProjectViewAssembler viewAssembler;
    private final ProjectCollaborationCoordinator collaborationService;
    private ProjectCreationCommand self;

    public ProjectCreationCommand(ProjectMapper projectMapper,
                                  ProjectCommandSupport commandSupport,
                                  ProjectViewAssembler viewAssembler,
                                  ProjectCollaborationCoordinator collaborationService,
                                  @Lazy ProjectCreationCommand self) {
        this.projectMapper = projectMapper;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.collaborationService = collaborationService;
        this.self = self;
    }

    public ProjectVO createProject(Long teamId, CreateProjectRequest request, Long operatorUserId) {
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        if (leaderUserId == null) {
            leaderUserId = operatorUserId;
        }
        // HTTP call outside transaction to avoid holding DB connection during remote I/O
        commandSupport.requireActiveTeamMember(teamId, leaderUserId);
        // DB operations in transaction
        Project project = self.doCreateProject(teamId, request, leaderUserId);
        // Post-transaction: IM conversation + view assembly
        postCreateProject(project, teamId, operatorUserId);
        return viewAssembler.toProjectVO(project, operatorUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Project doCreateProject(Long teamId, CreateProjectRequest request, Long leaderUserId) {
        commandSupport.validateProjectNameAvailable(teamId, request == null ? null : request.getName(), null);

        LocalDateTime now = LocalDateTime.now();
        Project project = new Project();
        project.setTeamId(teamId);
        project.setName(requireText(request == null ? null : request.getName(), "项目名称不能为空"));
        project.setDescription(optionalText(request == null ? null : request.getDescription()));
        project.setLeaderUserId(leaderUserId);
        project.setStatus(0);
        project.setCreateTime(now);
        project.setUpdateTime(now);
        projectMapper.insert(project);

        commandSupport.upsertMember(project.getId(), leaderUserId, ProjectRoleCodes.LEADER, now);
        commandSupport.upsertProjectQuota(project.getId(), commandSupport.normalizeStorageLimit(request == null ? null : request.getStorageLimit()));
        return project;
    }

    public void postCreateProject(Project project, Long teamId, Long operatorUserId) {
        Long conversationId = collaborationService.createProjectConversation(project.getId(), teamId, project.getName(), project.getLeaderUserId());
        if (conversationId != null) {
            self.updateConversationId(project.getId(), conversationId);
            project.setConversationId(conversationId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateConversationId(Long projectId, Long conversationId) {
        projectMapper.updateConversationId(projectId, conversationId);
    }

    // Package-private setter for unit testing without Spring proxy
    void setSelf(ProjectCreationCommand self) {
        this.self = self;
    }
}
