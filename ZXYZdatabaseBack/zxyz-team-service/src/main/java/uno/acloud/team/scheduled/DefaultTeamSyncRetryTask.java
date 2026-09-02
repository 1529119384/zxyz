package uno.acloud.team.scheduled;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uno.acloud.team.entity.TeamUserDefaultSync;
import uno.acloud.team.infrastructure.client.UserServiceClient;
import uno.acloud.team.mapper.TeamUserDefaultSyncMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * P2-A4：用户默认团队同步定时重试任务。
 *
 * <p>团队创建事务提交后（{@code EnterpriseTeamService.afterCommit}）仅向本地表
 * {@code team_user_default_sync} 写入一条 PENDING 记录，不再直接调用 user-service。
 * 本任务周期性扫描到点的 PENDING 记录，调用 {@code UserServiceClient.updateDefaultTeam}，
 * 成功置 DONE；失败按指数退避 + 抖动推进 next_retry_time，超过上限置 FAILED。</p>
 *
 * <p>updateDefaultTeam 为幂等写（仅设置用户默认团队），重复执行安全。</p>
 */
@Slf4j
@Component
public class DefaultTeamSyncRetryTask {

    /** 单次扫描最大处理条数 */
    private static final int BATCH_SIZE = 100;
    /** 基础退避时长：首次失败后 30s 再次尝试 */
    private static final long BASE_BACKOFF_MILLIS = 30_000L;
    /** 退避上限：最多退避到 30 分钟 */
    private static final long MAX_BACKOFF_MILLIS = 30 * 60_000L;
    /** 超过该重试次数则标记为 FAILED 放弃重试 */
    private static final int MAX_RETRY_COUNT = 10;

    private final TeamUserDefaultSyncMapper syncMapper;
    private final UserServiceClient userServiceClient;

    public DefaultTeamSyncRetryTask(TeamUserDefaultSyncMapper syncMapper,
                                    UserServiceClient userServiceClient) {
        this.syncMapper = syncMapper;
        this.userServiceClient = userServiceClient;
    }

    @Scheduled(
            initialDelayString = "${app.team.default-team-sync.initial-delay-ms:30000}",
            fixedDelayString = "${app.team.default-team-sync.fixed-delay-ms:30000}"
    )
    public void retryPendingSync() {
        try {
            List<TeamUserDefaultSync> due = syncMapper.listDuePending(BATCH_SIZE);
            if (due.isEmpty()) {
                return;
            }
            for (TeamUserDefaultSync row : due) {
                process(row);
            }
        } catch (Exception e) {
            log.warn("默认团队同步重试任务异常", e);
        }
    }

    private void process(TeamUserDefaultSync row) {
        try {
            userServiceClient.updateDefaultTeam(row.getUserId(), row.getTeamId());
            syncMapper.markDone(row.getId());
            log.info("默认团队同步成功，userId={}, teamId={}", row.getUserId(), row.getTeamId());
        } catch (Exception e) {
            int nextCount = (row.getRetryCount() == null ? 0 : row.getRetryCount()) + 1;
            if (nextCount >= MAX_RETRY_COUNT) {
                syncMapper.markFailed(row.getId());
                log.error("默认团队同步最终失败，放弃重试，userId={}, teamId={}, retryCount={}",
                        row.getUserId(), row.getTeamId(), nextCount, e);
            } else {
                LocalDateTime nextRetry = computeNextRetry(nextCount);
                syncMapper.markRetry(row.getId(), nextRetry);
                log.warn("默认团队同步失败，已安排重试，userId={}, teamId={}, retryCount={}, nextRetryTime={}",
                        row.getUserId(), row.getTeamId(), nextCount, nextRetry, e);
            }
        }
    }

    /**
     * 指数退避 + 抖动：base * 2^(n-1)，封顶 MAX_BACKOFF_MILLIS，
     * 并叠加 0~25% 的随机抖动避免多个待同步记录在同一时刻「惊群」重试。
     */
    private LocalDateTime computeNextRetry(int retryCount) {
        long exp = BASE_BACKOFF_MILLIS * (1L << Math.min(retryCount - 1, 10));
        long backoff = Math.min(exp, MAX_BACKOFF_MILLIS);
        long jitter = ThreadLocalRandom.current().nextLong(0, backoff / 4 + 1);
        return LocalDateTime.now().plus(Duration.ofMillis(backoff + jitter));
    }
}
