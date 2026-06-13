package uno.acloud.project.service;

import uno.acloud.project.dto.project.UpdateProjectQuotaRequest;
import uno.acloud.project.vo.project.ProjectVO;

public interface ProjectQuotaPort {

    ProjectVO updateProjectQuota(Long projectId, UpdateProjectQuotaRequest request, Long operatorUserId);
}
