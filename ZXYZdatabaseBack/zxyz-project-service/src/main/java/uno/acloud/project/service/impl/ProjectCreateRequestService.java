package uno.acloud.project.service.impl;

import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.dto.project.ReviewProjectCreateRequest;
import uno.acloud.project.dto.project.SubmitProjectCreateRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.mapper.ProjectCreateRequestMapper;
import uno.acloud.project.service.ProjectCreateRequestPort;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.time.LocalDateTime;
import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;
import static uno.acloud.common.InputNormalizer.requireText;

@Service
public class ProjectCreateRequestService implements ProjectCreateRequestPort {

    private final ProjectCreateRequestMapper projectCreateRequestMapper;
    private final TeamFileAccessPort teamFileAccessService;
    private final ProjectCommandSupport commandSupport;
    private final ProjectViewAssembler viewAssembler;
    private final ProjectCreationCommand projectCreationCommand;
    private final ProjectCollaborationCoordinator collaborationService;
    private ProjectCreateRequestService self;

    public ProjectCreateRequestService(ProjectCreateRequestMapper projectCreateRequestMapper,
                                       TeamFileAccessPort teamFileAccessService,
                                       ProjectCommandSupport commandSupport,
                                       ProjectViewAssembler viewAssembler,
                                       ProjectCreationCommand projectCreationCommand,
                                       ProjectCollaborationCoordinator collaborationService,
                                       @Lazy ProjectCreateRequestService self) {
        this.projectCreateRequestMapper = projectCreateRequestMapper;
        this.teamFileAccessService = teamFileAccessService;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.projectCreationCommand = projectCreationCommand;
        this.collaborationService = collaborationService;
        this.self = self;
    }

    @Override
    public ProjectCreateRequestVO submitProjectCreateRequest(Long teamId,
                                                             SubmitProjectCreateRequest request,
                                                             Long requesterUserId) {
        // Phase 1: Pre-transaction HTTP permission checks
        teamFileAccessService.requireTeamMember(teamId, requesterUserId);
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        if (leaderUserId == null) {
            leaderUserId = requesterUserId;
        }
        commandSupport.requireActiveTeamMember(teamId, leaderUserId);
        // Phase 2: DB transaction
        ProjectCreateRequest saved = self.doSubmitProjectCreateRequest(teamId, request, requesterUserId, leaderUserId);
        // Phase 3: Post-transaction HTTP (view assembly + IM notification)
        ProjectCreateRequestVO response = viewAssembler.toCreateRequestVO(saved);
        collaborationService.appendProjectCreateRequestMessage(toCreateRequestMessagePayload(saved, response));
        return response;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectCreateRequest doSubmitProjectCreateRequest(Long teamId,
                                                             SubmitProjectCreateRequest request,
                                                             Long requesterUserId,
                                                             Long leaderUserId) {
        commandSupport.validateProjectNameAvailable(teamId, request == null ? null : request.getName(), null);
        LocalDateTime now = LocalDateTime.now();
        ProjectCreateRequest entity = new ProjectCreateRequest();
        entity.setTeamId(teamId);
        entity.setRequesterUserId(requesterUserId);
        entity.setProjectName(requireText(request == null ? null : request.getName(), "项目名称不能为空"));
        entity.setDescription(optionalText(request == null ? null : request.getDescription()));
        entity.setLeaderUserId(leaderUserId);
        entity.setStorageLimit(commandSupport.normalizeStorageLimit(request == null ? null : request.getStorageLimit()));
        entity.setStatus(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        projectCreateRequestMapper.insert(entity);
        return projectCreateRequestMapper.selectById(entity.getId());
    }

    @Override
    public List<ProjectCreateRequestVO> listPendingProjectCreateRequests(Long teamId, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        return viewAssembler.toCreateRequestVOList(projectCreateRequestMapper.listPendingByTeamId(teamId));
    }

    @Override
    public ProjectVO approveProjectCreateRequest(Long applicationId,
                                           ReviewProjectCreateRequest reviewRequest,
                                           Long reviewerUserId) {
        // Phase 1: Pre-transaction HTTP permission checks
        ProjectCreateRequest application = commandSupport.requireProjectCreateRequest(applicationId);
        teamFileAccessService.check(reviewerUserId, application.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        commandSupport.validateProjectNameAvailable(application.getTeamId(), application.getProjectName(), applicationId);
        commandSupport.requireActiveTeamMember(application.getTeamId(), application.getLeaderUserId());
        // Phase 2: DB transaction (reviewPending + project creation in same transaction)
        String reason = optionalText(reviewRequest == null ? null : reviewRequest.getReason());
        Project project = self.doApproveProjectCreateRequest(application, reviewerUserId, reason);
        // Phase 3: Post-transaction HTTP (IM notifications + view assembly)
        projectCreationCommand.postCreateProject(project, application.getTeamId(), reviewerUserId);
        collaborationService.appendProjectCreateRequestReviewResultMessage(toCreateRequestReviewResultPayload(application, reviewerUserId, true, project.getId()));
        return viewAssembler.toProjectVO(project, reviewerUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Project doApproveProjectCreateRequest(ProjectCreateRequest application, Long reviewerUserId, String reason) {
        if (projectCreateRequestMapper.reviewPending(application.getId(), 1, reviewerUserId, reason) != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "申请已被处理");
        }
        CreateProjectRequest createRequest = new CreateProjectRequest();
        createRequest.setName(application.getProjectName());
        createRequest.setDescription(application.getDescription());
        createRequest.setLeaderUserId(application.getLeaderUserId());
        createRequest.setStorageLimit(application.getStorageLimit());
        return projectCreationCommand.doCreateProject(application.getTeamId(), createRequest, reviewerUserId);
    }

    @Override
    public ProjectCreateRequestVO rejectProjectCreateRequest(Long applicationId,
                                                       ReviewProjectCreateRequest reviewRequest,
                                                       Long reviewerUserId) {
        // Phase 1: Pre-transaction HTTP permission check
        ProjectCreateRequest application = commandSupport.requireProjectCreateRequest(applicationId);
        teamFileAccessService.check(reviewerUserId, application.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        // Phase 2: DB transaction
        String reason = optionalText(reviewRequest == null ? null : reviewRequest.getReason());
        self.doRejectProjectCreateRequest(applicationId, reviewerUserId, reason);
        // Phase 3: Post-transaction HTTP (IM notification + view assembly)
        application.setReviewReason(reason);
        collaborationService.appendProjectCreateRequestReviewResultMessage(toCreateRequestReviewResultPayload(application, reviewerUserId, false, null));
        return viewAssembler.toCreateRequestVO(projectCreateRequestMapper.selectById(applicationId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void doRejectProjectCreateRequest(Long applicationId, Long reviewerUserId, String reason) {
        if (projectCreateRequestMapper.reviewPending(applicationId, 2, reviewerUserId, reason) != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "申请已被处理");
        }
    }

    private ProjectCreateRequestMessagePayload toCreateRequestMessagePayload(ProjectCreateRequest application, ProjectCreateRequestVO response) {
        return new ProjectCreateRequestMessagePayload(
                application.getTeamId(),
                application.getId(),
                application.getRequesterUserId(),
                response.getRequesterName(),
                application.getProjectName(),
                application.getDescription(),
                application.getLeaderUserId(),
                response.getLeaderName(),
                application.getStorageLimit()
        );
    }

    private ProjectCreateRequestReviewResultPayload toCreateRequestReviewResultPayload(ProjectCreateRequest application,
                                                                          Long reviewerUserId,
                                                                          boolean approved,
                                                                          Long projectId) {
        return new ProjectCreateRequestReviewResultPayload(
                application.getTeamId(),
                application.getId(),
                reviewerUserId,
                approved,
                projectId,
                application.getProjectName(),
                application.getReviewReason()
        );
    }

    // Package-private setter for unit testing without Spring proxy
    void setSelf(ProjectCreateRequestService self) {
        this.self = self;
    }
}
