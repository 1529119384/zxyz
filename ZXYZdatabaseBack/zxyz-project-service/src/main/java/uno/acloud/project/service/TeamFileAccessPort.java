package uno.acloud.project.service;

import uno.acloud.common.permission.TeamPermissionPort;

/**
 * 团队文件访问端口，统一暴露文件领域需要复用的团队成员与空间校验能力。
 */
public interface TeamFileAccessPort extends TeamPermissionPort, TeamMembershipValidator {

    void requireTeamViewPermission(Long teamId, Long userId);

    void requireTeamWritePermission(Long teamId, Long userId);

    void requireTeamDeletePermission(Long teamId, Long userId);
}
