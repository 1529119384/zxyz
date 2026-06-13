package uno.acloud.team.service.impl;

import org.springframework.stereotype.Service;
import uno.acloud.common.ErrorCode;

import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.team.mapper.TeamFileAccessMapper;
import uno.acloud.team.service.TeamFileAccessPort;

@Service
public class TeamFileAccessService implements TeamFileAccessPort {

    private final TeamFileAccessMapper teamFileAccessMapper;
    private final TeamPermissionCacheService teamPermissionCacheService;

    public TeamFileAccessService(TeamFileAccessMapper teamFileAccessMapper,
                                 TeamPermissionCacheService teamPermissionCacheService) {
        this.teamFileAccessMapper = teamFileAccessMapper;
        this.teamPermissionCacheService = teamPermissionCacheService;
    }

    @Override
    public void requireTeamMember(Long teamId, Long userId) {
        if (teamId == null) {
            return;
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.NO_LOGIN, "用户未登录");
        }
        if (teamFileAccessMapper.countActiveMember(teamId, userId) < 1) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "你不在该团队中，无法访问团队文件");
        }
    }

    @Override
    public boolean hasPermission(Long userId, Long teamId, String permissionCode) {
        if (teamId == null) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        return teamPermissionCacheService.checkPermission(teamId, userId, permissionCode,
                () -> teamFileAccessMapper.countPermission(teamId, userId, permissionCode) > 0);
    }

    @Override
    public void check(long userId, long teamId, String permissionCode) {
        requireTeamMember(teamId, userId);
        if (!hasPermission(userId, teamId, permissionCode)) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "缺少团队权限: " + permissionCode);
        }
    }

    @Override
    public void requireTeamViewPermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_FILE_READ);
    }

    @Override
    public void requireTeamWritePermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_FILE_WRITE);
    }

    @Override
    public void requireTeamDeletePermission(Long teamId, Long userId) {
        check(userId, teamId, TeamPermissionCodes.TEAM_FILE_DELETE);
    }
}
