package uno.acloud.project.service;

import uno.acloud.project.dto.project.ReviewProjectCreateRequest;
import uno.acloud.project.dto.project.SubmitProjectCreateRequest;
import uno.acloud.project.vo.project.ProjectCreateRequestVO;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

public interface ProjectCreateRequestPort {

    ProjectCreateRequestVO submitProjectCreateRequest(Long teamId,
                                                       SubmitProjectCreateRequest request,
                                                       Long requesterUserId);

    List<ProjectCreateRequestVO> listPendingProjectCreateRequests(Long teamId, Long operatorUserId);

    ProjectVO approveProjectCreateRequest(Long applicationId,
                                    ReviewProjectCreateRequest reviewRequest,
                                    Long reviewerUserId);

    ProjectCreateRequestVO rejectProjectCreateRequest(Long applicationId,
                                                ReviewProjectCreateRequest reviewRequest,
                                                Long reviewerUserId);
}
