package uno.acloud.project.service.impl;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.project.dto.project.UpdateProjectQuotaRequest;
import uno.acloud.project.entity.Project;
import uno.acloud.exception.BusinessException;
import uno.acloud.project.service.ProjectAccessGuardPort;
import uno.acloud.project.service.ProjectQuotaPort;
import uno.acloud.project.vo.project.ProjectVO;

@Service
public class ProjectQuotaService implements ProjectQuotaPort {

    private final FileServiceClient fileServiceClient;
    private final ProjectAccessGuardPort projectAccessGuard;
    private final ProjectCommandSupport commandSupport;
    private final ProjectViewAssembler viewAssembler;
    private final ProjectQuotaService self;

    public ProjectQuotaService(FileServiceClient fileServiceClient,
                               ProjectAccessGuardPort projectAccessGuard,
                               ProjectCommandSupport commandSupport,
                               ProjectViewAssembler viewAssembler,
                               @Lazy ProjectQuotaService self) {
        this.fileServiceClient = fileServiceClient;
        this.projectAccessGuard = projectAccessGuard;
        this.commandSupport = commandSupport;
        this.viewAssembler = viewAssembler;
        this.self = self;
    }

    @Override
    public ProjectVO updateProjectQuota(Long projectId, UpdateProjectQuotaRequest request, Long operatorUserId) {
        Project project = projectAccessGuard.requireProjectManageAccess(projectId, operatorUserId);
        Long storageLimit = commandSupport.normalizeStorageLimit(request == null ? null : request.getStorageLimit());
        // HTTP calls outside transaction to avoid holding DB connection during remote I/O
        long usedStorage = fileServiceClient.sumActiveFileSize(operatorUserId, project.getTeamId(), 3, projectId);
        if (storageLimit != null && storageLimit < usedStorage) {
            throw new BusinessException(ErrorCode.FILE_STATE_INVALID, "项目空间配额不能小于当前已使用空间");
        }
        // DB operations via proxy to ensure proper transaction boundary
        self.doUpdateProjectQuota(projectId, storageLimit);
        // HTTP call for view assembly outside transaction
        return viewAssembler.toProjectVO(project, operatorUserId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void doUpdateProjectQuota(Long projectId, Long storageLimit) {
        commandSupport.upsertProjectQuota(projectId, storageLimit);
    }
}
