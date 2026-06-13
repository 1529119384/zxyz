package uno.acloud.team.service;

import uno.acloud.team.dto.system.BroadcastSystemMessageRequest;
import uno.acloud.team.dto.system.ScheduledEmailBatchRequest;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

import java.util.List;

public interface AdminTeamPort {

    List<AdminTeamOverviewVO> listTeams();

    AdminTeamOverviewVO updateTeamQuota(Long teamId, UpdateTeamQuotaRequest request);

    void broadcastSystemMessage(BroadcastSystemMessageRequest request);

    void scheduleSystemEmailBatch(ScheduledEmailBatchRequest request);
}
