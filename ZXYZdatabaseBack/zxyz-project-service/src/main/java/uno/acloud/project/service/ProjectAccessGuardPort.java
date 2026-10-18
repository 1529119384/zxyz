package uno.acloud.project.service;

import uno.acloud.project.entity.Project;

/**
 * 项目访问守卫，供文件、存储等非项目用例复用项目权限校验。
 */
public interface ProjectAccessGuardPort {

    Project requireProjectAccess(Long projectId, Long userId);

    Project requireProjectFileAccess(Long projectId, Long userId);

    Project requireProjectManageAccess(Long projectId, Long userId);

    /**
     * 65 号 #6：查询项目归属团队（file-service 上传链路 teamId 伪造校验用）。
     */
    Long getTeamId(Long projectId);

    /** 统计用户负责的活跃项目数（内部投影）。 */
    int countActiveProjectsLedBy(Long userId);

    /** 查询团队下所有项目配额总和（内部投影）。 */
    long sumProjectQuotaByTeam(Long teamId);
}
