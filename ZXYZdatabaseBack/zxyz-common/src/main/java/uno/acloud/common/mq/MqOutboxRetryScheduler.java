package uno.acloud.common.mq;

import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
public class MqOutboxRetryScheduler {

    private final MqOutboxPublisher outboxPublisher;

    public MqOutboxRetryScheduler(MqOutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
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
}
