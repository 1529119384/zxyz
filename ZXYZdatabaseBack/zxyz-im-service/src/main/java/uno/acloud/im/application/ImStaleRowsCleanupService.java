package uno.acloud.im.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uno.acloud.im.infrastructure.mapper.SystemNotificationMapper;
import uno.acloud.im.infrastructure.mapper.TeamInvitationMapper;
import uno.acloud.im.infrastructure.mapper.TeamManagementMapper;

import java.time.LocalDateTime;

/**
 * IM 三表分批清理任务（66 号二.5）：system_notification / team_invitation /
 * team_join_request 零清理导致的无限增长，照 audit 库 operate_log 分批 DELETE 协议实现。
 *
 * <p>保留策略：已终态（通知已读、邀请/申请已处理或过期）且超过保留期的行删除；
 * 待处理行永不清理。每批独立提交（mapper 单语句 LIMIT 自治），{@code affected == 0}
 * 或不足一批时退出，剩余批次次日收敛。</p>
 */
@Slf4j
@Service
public class ImStaleRowsCleanupService {

    static final int BATCH_LIMIT = 1000;
    static final int MAX_BATCHES = 100_000;

    private final SystemNotificationMapper systemNotificationMapper;
    private final TeamInvitationMapper teamInvitationMapper;
    private final TeamManagementMapper teamManagementMapper;
    /** 保留天数，默认 90 天 */
    private final int retentionDays;

    public ImStaleRowsCleanupService(SystemNotificationMapper systemNotificationMapper,
                                     TeamInvitationMapper teamInvitationMapper,
                                     TeamManagementMapper teamManagementMapper,
                                     @Value("${app.im.stale-rows.retention-days:90}") int retentionDays) {
        this.systemNotificationMapper = systemNotificationMapper;
        this.teamInvitationMapper = teamInvitationMapper;
        this.teamManagementMapper = teamManagementMapper;
        this.retentionDays = retentionDays;
    }

    /** 每日 03:40（错开 audit 03:00 与其它 03 点档任务） */
    @Scheduled(cron = "0 40 3 * * ?")
    public void cleanupStaleRows() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("开始清理 IM 过期行：删除 {} 之前已终态记录（每批 {} 条）", cutoff, BATCH_LIMIT);
        long totalNotification = deleteBatch("system_notification", cutoff);
        long totalInvitation = deleteBatch("team_invitation", cutoff);
        long totalJoin = deleteBatch("team_join_request", cutoff);
        log.info("IM 过期行清理完成: notification={}, invitation={}, joinRequest={}",
                totalNotification, totalInvitation, totalJoin);
    }

    private long deleteBatch(String table, LocalDateTime cutoff) {
        long total = 0;
        try {
            for (int batch = 1; batch <= MAX_BATCHES; batch++) {
                int deleted = switch (table) {
                    case "system_notification" ->
                            systemNotificationMapper.deleteReadOlderThanBatch(cutoff, BATCH_LIMIT);
                    case "team_invitation" ->
                            teamInvitationMapper.deleteTerminatedOlderThanBatch(cutoff, BATCH_LIMIT);
                    case "team_join_request" ->
                            teamManagementMapper.deleteJoinRequestTerminatedOlderThanBatch(cutoff, BATCH_LIMIT);
                    default -> 0;
                };
                total += deleted;
                if (deleted == 0 || deleted < BATCH_LIMIT) {
                    break;
                }
            }
        } catch (Exception e) {
            // 单表失败不拖垮其它表（与 AuditLogCleanupService 同款隔离纪律）
            log.error("IM 表 {} 分批清理异常，跳到下一表", table, e);
        }
        return total;
    }
}
