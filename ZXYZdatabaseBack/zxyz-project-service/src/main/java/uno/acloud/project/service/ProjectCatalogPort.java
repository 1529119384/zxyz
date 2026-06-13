package uno.acloud.project.service;

import uno.acloud.project.dto.project.CreateProjectRequest;
import uno.acloud.project.vo.project.ProjectVO;

import java.util.List;

public interface ProjectCatalogPort {

    List<ProjectVO> listVisibleProjects(Long teamId, Long userId);

    ProjectVO createProject(Long teamId, CreateProjectRequest request, Long operatorUserId);

    ProjectVO archiveProject(Long projectId, Long operatorUserId);
}
