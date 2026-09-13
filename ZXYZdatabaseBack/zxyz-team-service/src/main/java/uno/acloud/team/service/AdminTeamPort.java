package uno.acloud.team.service;

import uno.acloud.common.PageResult;
import uno.acloud.team.dto.system.BroadcastSystemMessageRequest;
import uno.acloud.team.dto.system.ScheduledEmailBatchRequest;
import uno.acloud.team.dto.team.UpdateTeamQuotaRequest;
import uno.acloud.team.vo.team.AdminTeamOverviewVO;

public interface AdminTeamPort {

    /**
     * 分页查团队概览（管理端列表）。
     *
     * @param page     页码，从 1 起；{@code null} 或小于 1 回落到第 1 页
     * @param pageSize 每页条数；{@code null} 取默认 20，超过 200 会被钳制到 200
     */
    PageResult<AdminTeamOverviewVO> listTeams(Integer page, Integer pageSize);

    AdminTeamOverviewVO updateTeamQuota(Long teamId, UpdateTeamQuotaRequest request);

    void broadcastSystemMessage(BroadcastSystemMessageRequest request);

    void scheduleSystemEmailBatch(ScheduledEmailBatchRequest request);
}
