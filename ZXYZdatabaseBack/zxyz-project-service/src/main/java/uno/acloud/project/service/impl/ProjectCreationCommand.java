package uno.acloud.project.service.impl;

import org.springframework.stereotype.Component;
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

    public ProjectCreationCommand(ProjectMapper projectMapper,
                                  ProjectCommandSupport commandSupport,
                                  ProjectViewAssembler viewAssembler,
                                  ProjectCollaborationCoordinator collaborationService) {
        this.projectMapper = projectMapper;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.collaborationService = collaborationService;
    }

    public ProjectVO createProject(Long teamId, CreateProjectRequest request, Long operatorUserId) {
        // 项目创建同时服务手动创建和申请审批，集中在命令组件中避免服务实现互相调用。
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        if (leaderUserId == null) {
            leaderUserId = operatorUserId;
        }
        commandSupport.requireActiveTeamMember(teamId, leaderUserId);
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
        Long conversationId = collaborationService.createProjectConversation(project.getId(), teamId, project.getName(), leaderUserId);
        if (conversationId != null) {
            projectMapper.updateConversationId(project.getId(), conversationId);
            project.setConversationId(conversationId);
        }
        return viewAssembler.toProjectVO(project, operatorUserId);
    }
}
