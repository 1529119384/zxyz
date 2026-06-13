package uno.acloud.project.service.impl;

import uno.acloud.project.service.TeamFileAccessPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.dto.project.ReviewProjectCreateRequest;
import uno.acloud.project.dto.project.SubmitProjectCreateRequest;
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

    public ProjectCreateRequestService(ProjectCreateRequestMapper projectCreateRequestMapper,
                                       TeamFileAccessPort teamFileAccessService,
                                       ProjectCommandSupport commandSupport,
                                       ProjectViewAssembler viewAssembler,
                                       ProjectCreationCommand projectCreationCommand,
                                       ProjectCollaborationCoordinator collaborationService) {
        this.projectCreateRequestMapper = projectCreateRequestMapper;
        this.teamFileAccessService = teamFileAccessService;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.projectCreationCommand = projectCreationCommand;
        this.collaborationService = collaborationService;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProjectCreateRequestVO submitProjectCreateRequest(Long teamId,
                                                             SubmitProjectCreateRequest request,
                                                             Long requesterUserId) {
        teamFileAccessService.requireTeamMember(teamId, requesterUserId);
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        if (leaderUserId == null) {
            leaderUserId = requesterUserId;
        }
        commandSupport.requireActiveTeamMember(teamId, leaderUserId);
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
        ProjectCreateRequest saved = projectCreateRequestMapper.selectById(entity.getId());
        ProjectCreateRequestVO response = viewAssembler.toCreateRequestVO(saved);
        collaborationService.appendProjectCreateRequestMessage(toCreateRequestMessagePayload(saved, response));
        return response;
    }

    @Override
    public List<ProjectCreateRequestVO> listPendingProjectCreateRequests(Long teamId, Long operatorUserId) {
        teamFileAccessService.check(operatorUserId, teamId, TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        return viewAssembler.toCreateRequestVOList(projectCreateRequestMapper.listPendingByTeamId(teamId));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProjectVO approveProjectCreateRequest(Long applicationId,
                                           ReviewProjectCreateRequest reviewRequest,
                                           Long reviewerUserId) {
        ProjectCreateRequest application = commandSupport.requireProjectCreateRequest(applicationId);
        teamFileAccessService.check(reviewerUserId, application.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        commandSupport.validateProjectNameAvailable(application.getTeamId(), application.getProjectName(), applicationId);
        String reason = optionalText(reviewRequest == null ? null : reviewRequest.getReason());
        if (projectCreateRequestMapper.reviewPending(applicationId, 1, reviewerUserId, reason) != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "申请已被处理");
        }
        application.setReviewReason(reason);

        CreateProjectRequest createRequest = new CreateProjectRequest();
        createRequest.setName(application.getProjectName());
        createRequest.setDescription(application.getDescription());
        createRequest.setLeaderUserId(application.getLeaderUserId());
        createRequest.setStorageLimit(application.getStorageLimit());
        ProjectVO project = projectCreationCommand.createProject(application.getTeamId(), createRequest, reviewerUserId);
        collaborationService.appendProjectCreateRequestReviewResultMessage(toCreateRequestReviewResultPayload(application, reviewerUserId, true, project.getId()));
        return project;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ProjectCreateRequestVO rejectProjectCreateRequest(Long applicationId,
                                                       ReviewProjectCreateRequest reviewRequest,
                                                       Long reviewerUserId) {
        ProjectCreateRequest application = commandSupport.requireProjectCreateRequest(applicationId);
        teamFileAccessService.check(reviewerUserId, application.getTeamId(), TeamPermissionCodes.TEAM_PROJECT_MANAGE);
        String reason = optionalText(reviewRequest == null ? null : reviewRequest.getReason());
        if (projectCreateRequestMapper.reviewPending(applicationId, 2, reviewerUserId, reason) != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_OPERATION, "申请已被处理");
        }
        application.setReviewReason(reason);
        collaborationService.appendProjectCreateRequestReviewResultMessage(toCreateRequestReviewResultPayload(application, reviewerUserId, false, null));
        return viewAssembler.toCreateRequestVO(projectCreateRequestMapper.selectById(applicationId));
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
}
