package uno.acloud.project.service.impl;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.project.common.ProjectRoleCodes;
import uno.acloud.project.dto.project.AddProjectMemberRequest;
import uno.acloud.project.dto.project.TransferProjectLeaderRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mq.ProjectEventPublisher;
import uno.acloud.project.service.ProjectAccessGuardPort;
import uno.acloud.project.service.ProjectMemberPort;
import uno.acloud.project.vo.project.ProjectMemberVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectMemberService implements ProjectMemberPort {

    private final ProjectMapper projectMapper;
    private final ProjectAccessGuardPort projectAccessGuard;
    private final ProjectCommandSupport commandSupport;
    private final ProjectViewAssembler viewAssembler;
    private final ProjectEventPublisher eventPublisher;
    private ProjectMemberService self;

    public ProjectMemberService(ProjectMapper projectMapper,
                                ProjectAccessGuardPort projectAccessGuard,
                                ProjectCommandSupport commandSupport,
                                ProjectViewAssembler viewAssembler,
                                ProjectEventPublisher eventPublisher,
                                @Lazy ProjectMemberService self) {
        this.projectMapper = projectMapper;
        this.projectAccessGuard = projectAccessGuard;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.eventPublisher = eventPublisher;
        this.self = self;
    }

    @Override
    public List<ProjectMemberVO> listMembers(Long projectId, Long userId) {
        Project project = projectAccessGuard.requireProjectAccess(projectId, userId);
        return viewAssembler.toMemberVOList(projectMapper.listMembers(project.getId()));
    }

    @Override
    public ProjectMemberVO addMember(Long projectId, AddProjectMemberRequest request, Long operatorUserId) {
        // Phase 1: Pre-transaction HTTP permission checks
        Project project = projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId);
        Long targetUserId = request == null ? null : request.getUserId();
        if (targetUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        commandSupport.requireActiveTeamMember(project.getTeamId(), targetUserId);
        // Phase 2: DB transaction
        self.doAddMember(projectId, targetUserId);
        // Phase 3: Post-transaction MQ event + view assembly
        eventPublisher.publishMemberAdded(projectId, targetUserId);
        return viewAssembler.toMemberVOList(projectMapper.listMembers(projectId)).stream()
                .filter(vo -> targetUserId.equals(vo.getUserId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "项目成员创建后读取失败"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void doAddMember(Long projectId, Long targetUserId) {
        commandSupport.upsertMember(projectId, targetUserId, ProjectRoleCodes.MEMBER, LocalDateTime.now());
    }

    @Override
    public ProjectVO transferLeader(Long projectId, TransferProjectLeaderRequest request, Long operatorUserId) {
        // Phase 1: Pre-transaction HTTP permission checks
        Project project = projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId);
        Long leaderUserId = request == null ? null : request.getLeaderUserId();
        if (leaderUserId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "leaderUserId 不能为空");
        }
        commandSupport.requireActiveTeamMember(project.getTeamId(), leaderUserId);
        // Phase 2: DB transaction
        self.doTransferLeader(projectId, leaderUserId, project);
        // Phase 3: Post-transaction view assembly (HTTP to user-service)
        return viewAssembler.toProjectVO(project, operatorUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void doTransferLeader(Long projectId, Long leaderUserId, Project project) {
        commandSupport.upsertMember(projectId, leaderUserId, ProjectRoleCodes.LEADER, LocalDateTime.now());
        projectMapper.updateLeader(projectId, leaderUserId);
        project.setLeaderUserId(leaderUserId);
    }

    // Package-private setter for unit testing without Spring proxy
    void setSelf(ProjectMemberService self) {
        this.self = self;
    }
}
