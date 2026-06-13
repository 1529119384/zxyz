package uno.acloud.project.service;

import uno.acloud.project.entity.Project;

/**
 * 项目访问守卫，供文件、存储等非项目用例复用项目权限校验。
 */
public interface ProjectAccessGuardPort {

    Project requireProjectAccess(Long projectId, Long userId);

    Project requireProjectFileAccess(Long projectId, Long userId);

    Project requireProjectManageAccess(Long projectId, Long userId);
}
