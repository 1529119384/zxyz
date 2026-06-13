package uno.acloud.team.service;

import uno.acloud.common.permission.TeamPermissionPort;

public interface TeamFileAccessPort extends TeamPermissionPort, TeamMembershipValidator {

    void requireTeamViewPermission(Long teamId, Long userId);

    void requireTeamWritePermission(Long teamId, Long userId);

    void requireTeamDeletePermission(Long teamId, Long userId);
}
