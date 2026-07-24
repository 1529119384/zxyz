package uno.acloud.project.service.impl;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamErrorCode;
import uno.acloud.common.ProjectErrorCode;
import uno.acloud.project.entity.ProjectCreateRequest;
import uno.acloud.project.entity.ProjectMember;
import uno.acloud.project.entity.ProjectQuota;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.mapper.ProjectCreateRequestMapper;
import uno.acloud.project.mapper.ProjectMapper;
import uno.acloud.project.mapper.ProjectQuotaMapper;

import java.time.LocalDateTime;

import static uno.acloud.common.InputNormalizer.requireText;

@Component
public class ProjectCommandSupport {

    private final ProjectMapper projectMapper;
    private final ProjectCreateRequestMapper projectCreateRequestMapper;
    private final ProjectQuotaMapper projectQuotaMapper;
    private final TeamServiceClient teamServiceClient;

    public ProjectCommandSupport(ProjectMapper projectMapper,
                                 ProjectCreateRequestMapper projectCreateRequestMapper,
                                 ProjectQuotaMapper projectQuotaMapper,
                                 TeamServiceClient teamServiceClient) {
        this.projectMapper = projectMapper;
        this.projectCreateRequestMapper = projectCreateRequestMapper;
        this.projectQuotaMapper = projectQuotaMapper;
        this.teamServiceClient = teamServiceClient;
    }

    public void requireActiveTeamMember(Long teamId, Long userId) {
        if (!teamServiceClient.isActiveMember(teamId, userId)) {
            throw new BusinessException(TeamErrorCode.TEAM_PERMISSION_DENIED.getCode(), "用户不在该团队中");
        }
    }

    public void validateProjectNameAvailable(Long teamId, String name, Long excludeApplicationId) {
        String normalizedName = requireText(name, "项目名称不能为空");
        if (projectMapper.countActiveByTeamIdAndName(teamId, normalizedName) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同一团队下已存在同名项目");
        }
        if (projectCreateRequestMapper.countPendingSameName(teamId, normalizedName, excludeApplicationId) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "同一团队下已存在同名项目申请");
        }
    }

    public void upsertMember(Long projectId, Long userId, String roleCode, LocalDateTime now) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRoleCode(roleCode);
        member.setJoinTime(now);
        projectMapper.upsertMember(member);
    }

    public void upsertProjectQuota(Long projectId, Long storageLimit) {
        ProjectQuota quota = new ProjectQuota();
        quota.setProjectId(projectId);
        quota.setStorageLimit(storageLimit);
        quota.setCreateTime(LocalDateTime.now());
        quota.setUpdateTime(LocalDateTime.now());
        projectQuotaMapper.upsertQuota(quota);
    }

    public ProjectCreateRequest requireProjectCreateRequest(Long applicationId) {
        ProjectCreateRequest application = projectCreateRequestMapper.selectById(applicationId);
        if (application == null) {
            throw new BusinessException(ProjectErrorCode.PROJECT_CREATE_REQUEST_NOT_FOUND.getCode(), "项目组申请不存在");
        }
        return application;
    }

    @Nullable
    public Long normalizeStorageLimit(Long value) {
        if (value == null) {
            return null;
        }
        if (value <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目空间配额必须大于 0，或留空表示无限");
        }
        return value;
    }
}
