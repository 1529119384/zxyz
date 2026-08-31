package uno.acloud.im.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.im.config.InviteLinkProperties;
import uno.acloud.im.infrastructure.persistence.entity.TeamInviteLink;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;
import uno.acloud.im.dto.CreateInviteLinkRequest;
import uno.acloud.im.vo.TeamInviteLinkVO;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class TeamManagementService {

    private final TeamManagementMapper managementMapper;
    private final TeamPermissionService teamPermissionService;
    private final int defaultExpireHours;
    private final int defaultMaxUses;

    public TeamManagementService(TeamManagementMapper managementMapper,
                                 TeamPermissionService teamPermissionService,
                                 InviteLinkProperties inviteLinkProperties) {
        this.managementMapper = managementMapper;
        this.teamPermissionService = teamPermissionService;
        this.defaultExpireHours = inviteLinkProperties.getDefaultExpireHours();
        this.defaultMaxUses = inviteLinkProperties.getDefaultMaxUses();
    }

    @Transactional(rollbackFor = Exception.class)
    public TeamInviteLinkVO createInviteLink(Long operatorUserId, Long teamId, CreateInviteLinkRequest request) {
        teamPermissionService.requirePermission(teamId, operatorUserId, TeamPermissionCodes.TEAM_INVITE_LINK_MANAGE);
        int expireHours = request != null && request.getExpireHours() != null && request.getExpireHours() > 0
                ? request.getExpireHours()
                : defaultExpireHours;
        int maxUses = request != null && request.getMaxUses() != null && request.getMaxUses() >= 0
                ? request.getMaxUses()
                : defaultMaxUses;
        LocalDateTime now = LocalDateTime.now();
        TeamInviteLink link = new TeamInviteLink();
        link.setTeamId(teamId);
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setCreatedByUserId(operatorUserId);
        link.setExpireTime(now.plusHours(expireHours));
        link.setMaxUses(maxUses);
        link.setUsedCount(0);
        link.setStatus(0);
        link.setCreateTime(now);
        link.setUpdateTime(now);
        managementMapper.insertInviteLink(link);
        return toInviteLinkVO(link);
    }

    private TeamInviteLinkVO toInviteLinkVO(TeamInviteLink link) {
        return new TeamInviteLinkVO(link.getId(), link.getTeamId(), link.getToken(),
                "/join/team/" + link.getToken(), link.getMaxUses(), link.getUsedCount(),
                link.getExpireTime(), link.getCreateTime());
    }
}
