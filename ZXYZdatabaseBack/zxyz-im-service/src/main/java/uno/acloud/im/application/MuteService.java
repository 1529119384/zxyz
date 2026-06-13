package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.exception.BusinessException;
import uno.acloud.im.domain.model.ImConversation;
import uno.acloud.im.domain.model.TeamMute;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;
import uno.acloud.im.dto.MuteMemberRequest;
import uno.acloud.im.vo.TeamMuteVO;

import java.util.List;

import static uno.acloud.common.InputNormalizer.optionalText;

@Service
public class MuteService {

    private final TeamService teamService;
    private final TeamManagementMapper managementMapper;
    private final TeamPermissionService teamPermissionService;

    public MuteService(TeamService teamService,
                       TeamManagementMapper managementMapper,
                       TeamPermissionService teamPermissionService) {
        this.teamService = teamService;
        this.managementMapper = managementMapper;
        this.teamPermissionService = teamPermissionService;
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamMuteVO muteMember(Long operatorUserId, Long teamId, MuteMemberRequest request) {
        Long targetUserId = request == null ? null : request.getUserId();
        if (targetUserId == null || targetUserId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        if (operatorUserId.equals(targetUserId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能禁言自己");
        }
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_MUTE_MANAGE);
        teamService.requireActiveMember(teamId, targetUserId);

        TeamMute mute = new TeamMute();
        mute.setTeamId(teamId);
        mute.setUserId(targetUserId);
        mute.setMutedByUserId(operatorUserId);
        mute.setReason(optionalText(request.getReason()));
        mute.setExpireTime(request.getExpireTime());
        managementMapper.upsertMute(mute);
        return managementMapper.listActiveMutes(teamId).stream()
                .filter(item -> targetUserId.equals(item.getUserId()))
                .findFirst()
                .orElse(null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unmuteMember(Long operatorUserId, Long teamId, Long targetUserId) {
        if (targetUserId == null || targetUserId < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "userId 不能为空");
        }
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_MUTE_MANAGE);
        teamService.requireActiveMember(teamId, targetUserId);
        managementMapper.disableMute(teamId, targetUserId);
    }

    public List<TeamMuteVO> listMutes(Long userId, Long teamId) {
        teamPermissionService.requirePermission(teamId, userId, TeamPermissionCodes.TEAM_MUTE_MANAGE);
        return managementMapper.listActiveMutes(teamId);
    }

    public void requireCanSend(Long userId, ImConversation conversation) {
        if (conversation == null || conversation.getTeamId() == null) {
            return;
        }
        TeamMute mute = managementMapper.getActiveMute(conversation.getTeamId(), userId);
        if (mute != null) {
            throw new BusinessException(ErrorCode.TEAM_PERMISSION_DENIED, "你已被团队禁言，暂时无法发送消息");
        }
    }
}
