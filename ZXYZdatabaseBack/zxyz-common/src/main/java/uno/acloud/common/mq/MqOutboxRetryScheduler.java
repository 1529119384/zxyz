package uno.acloud.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * outbox 定时补偿重投器：周期调用 {@link MqOutboxPublisher#retryPending()}，
 * 把发布失败落入 Redis outbox 的领域事件重发到 MQ（成功即删键）。
 *
 * <p><b>调度前提</b>：与 {@code AuditBufferRetryScheduler} 同族 —— 依赖宿主服务已开启
 * {@code @EnableScheduling}（team / user 均已开启）。本 Bean 由
 * {@code MqOutboxAutoConfiguration} 以 {@code @ConditionalOnBean(ScheduledAnnotationBeanPostProcessor.class)}
 * 守卫注册：未开启调度的服务不会凭空多出一个永不触发的定时任务（C-12 教训）。</p>
 *
 * <p><b>频率</b>：默认 30 秒一轮（与审计缓冲重试一致），可经
 * {@code app.mq.outbox.retry-interval-ms} 覆盖。单轮批量上限
 * {@link MqOutboxPublisher#MAX_RETRY_BATCH}，失败风暴时退化为多轮消化，不占满调度线程。</p>
 *
 * <p><b>66 号二.6（2026-11-02 补）：积压/TTL 监控</b> —— 重投只解决「能发出去」，
 * 但「一直发不出去」是静默的：键会在 24h TTL 到期后被 Redis 删除，事件永久丢失且无人知道。
 * 因此本类另加一个独立周期的积压巡检：积压数超阈值，或存在「剩余 TTL 低于告警线」
 * 的键（= 已经重试了接近 22 小时仍未成功）时，打 WARN 日志。
 * 日志可被 Loki 采集/告警（observability 栈已含 Loki），不必再依赖 Redis exporter。</p>
 */
@Slf4j
public class MqOutboxRetryScheduler {

    private final MqOutboxPublisher outboxPublisher;
    /** 积压条数告警阈值 */
    private final int backlogWarnThreshold;
    /** 剩余 TTL 告警线（秒）：低于该值说明该事件已重试接近 TTL 上限 */
    private final long nearExpirySeconds;

    public MqOutboxRetryScheduler(MqOutboxPublisher outboxPublisher,
                                  @Value("${app.mq.outbox.backlog-warn-threshold:100}")
                                  int backlogWarnThreshold,
                                  @Value("${app.mq.outbox.near-expiry-seconds:7200}")
                                  long nearExpirySeconds) {
        this.outboxPublisher = outboxPublisher;
        this.backlogWarnThreshold = backlogWarnThreshold;
        this.nearExpirySeconds = nearExpirySeconds;
    }

    @Scheduled(fixedDelayString = "${app.mq.outbox.retry-interval-ms:30000}",
            initialDelayString = "${app.mq.outbox.retry-initial-delay-ms:15000}")
    public void retryPendingOutboxEvents() {
        if (outboxPublisher == null) {
            return; // 60 号收口：disabled 哨兵（测试上下文无 RabbitTemplate ⇒ publisher 不创建）——直接返回，不碰 redis
        }
        try {
            int retried = outboxPublisher.retryPending();
            if (retried > 0) {
                log.info("outbox 定时补偿: 成功重发 {} 条事件", retried);
            }
        } catch (Exception e) {
            // @Scheduled 任务绝不能向外抛异常：抛出会让后续调度被取消（AuditLogCleanupService 同款纪律）
            log.error("outbox 定时补偿任务异常", e);
        }
    }

    /**
     * 积压/TTL 巡检（66 号二.6）。独立周期，与重投解耦：
     * 重投失败风暴时也能持续报告积压，而不是只在重投日志里刷同一行。
     */
    @Scheduled(fixedDelayString = "${app.mq.outbox.monitor-interval-ms:300000}",
            initialDelayString = "${app.mq.outbox.monitor-initial-delay-ms:60000}")
    public void monitorOutboxBacklog() {
        if (outboxPublisher == null) {
            return;
        }
        try {
            MqOutboxPublisher.BacklogStats stats = outboxPublisher.backlogStats();
            if (stats.isEmpty()) {
                return;
            }
            boolean overCount = stats.pending() >= backlogWarnThreshold;
            boolean nearExpiry = stats.minRemainingTtlSecs() >= 0
                    && stats.minRemainingTtlSecs() <= nearExpirySeconds;
            if (overCount || nearExpiry) {
                // ⚠️ 这一行是告警入口：出现即意味着 outbox 有事件长期发不出去，
                // TTL 到期后将被 Redis 删除（事件永久丢失）。排查顺序：
                //   RabbitMQ 可用性 → exchange/routing key → 消费端拒绝（DLQ）→ 网络。
                log.warn("outbox 积压告警: pending={} minRemainingTtlSecs={} 阈值(条)={} 告警线(秒)={}",
                        stats.pending(), stats.minRemainingTtlSecs(),
                        backlogWarnThreshold, nearExpirySeconds);
            }
        } catch (Exception e) {
            log.error("outbox 积压巡检任务异常", e);
        }
    }
}

