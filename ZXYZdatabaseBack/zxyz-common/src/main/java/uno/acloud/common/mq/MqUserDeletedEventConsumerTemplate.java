package uno.acloud.common.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.RabbitMqConstants;
import uno.acloud.common.event.UserDeletedEvent;

/**
 * {@code user.deleted} 消费骨架的公共模板（60 号审计 🟡-5 第 1 组收敛）。
 *
 * <p><b>为什么必须收敛</b>：file / project / share / team 四份 {@code UserDeletedEventConsumer}
 * 逐字同构（解析 → 过滤 → 幂等认领 → 清理 → 成功标记/失败释放 → 毒消息进 DLQ），
 * 且幂等语义一度分叉（🟠-4：file 已修两段式，其余三处还是裸 24h 占位键）。
 * 「一处修了、五处没修」的结构性根因就是同型代码各写各的；骨架收敛到一处后，
 * 修复只需落在一个文件里。幂等语义统一走 {@link MqUserDeletedCleanupSupport}。</p>
 *
 * <h2>骨架的既定语义（与 file 版逐条对齐，勿在各服务里重新发明）</h2>
 * <ol>
 *   <li><b>事件过滤</b>：{@code eventType != user.deleted} 时 debug 日志后直接返回，
 *       <b>不占用幂等键</b>；</li>
 *   <li><b>认领失败分双分支</b>：先问 {@link MqUserDeletedCleanupSupport#isCleanupCompleted} ——
 *       已完成 ⇒ info「重复投递，跳过」；未完成（并发或上次崩溃的短 TTL 未过期）⇒
 *       warn「正在处理中，本次跳过」。绝不允许合并成一句「重复事件」：
 *       file 版的教训是那会把「半途而废」这种真实故障伪装成正常幂等命中；</li>
 *   <li><b>成功才写 done 键</b>（{@code markCleanupCompleted}）：崩溃窗口内不写，
 *       重投递最多 {@link MqUserDeletedCleanupSupport#PROCESSING_TTL_MINUTES} 分钟后接管；</li>
 *   <li><b>失败释放 processing 键再重抛</b>：抛出是为了让容器的重试（3 次指数退避）生效，
 *       释放是为了重投不被旧认领键拦下。释放经 {@link MqIdempotencyKeys#release}——
 *       Redis 异常只记日志，不掩盖原始业务异常；</li>
 *   <li><b>反序列化失败抛 {@link AmqpRejectAndDontRequeueException}</b>：
 *       毒消息（坏 JSON / 类型不匹配）直接进 DLQ，绝不 log-and-return 静默 ACK。</li>
 * </ol>
 *
 * <p><b>为什么是组合而不是抽象基类</b>：四处消费端唯一的自由度是 {@code @RabbitListener}
 * 的队列常量（各服务 RabbitMqConfig 不同），注解必须留在具体类上；若做成抽象基类，
 * 监听方法与注解都要在每个子类重新写一遍，收敛反而退化成新的复制面。
 * 组合 + 一行委托让子类退化成「队列声明 + 委托」两件事，行为全部来自公共实现。</p>
 *
 * <p><b>测试口径</b>：允许（并鼓励）直接对本模板写单测；各服务消费端测试只需钉住
 * 「队列常量 + 委托转发」即可，事件 wire 契约由 zxyz-common 的 MqWireContractTest 兜底。</p>
 */
@Slf4j
public class MqUserDeletedEventConsumerTemplate {

    private final ObjectMapper objectMapper;
    private final Cleanup cleanup;

    /** 各服务清理动作的最小接口：实现方即各服务的 {@code *UserCleanupService}。 */
    public interface Cleanup {

        /** 幂等认领（两段式 processing 键），见 {@link MqUserDeletedCleanupSupport#tryAcquireIdempotencyKey}。 */
        boolean tryAcquireIdempotencyKey(long userId);

        /** 清理主体（成功后由模板标记完成；抛异常 ⇒ 模板释放认领并重抛）。 */
        void cleanup(long userId);

        /** 清理<b>成功</b>后调用：写长 TTL done 键并释放 processing 键。 */
        void markCleanupCompleted(long userId);

        /** 该用户的清理是否已真正完成（区分「已完成」与「处理中」的日志口径）。 */
        boolean isCleanupCompleted(long userId);

        /** 释放「处理中」认领（失败回滚路径）。 */
        void releaseIdempotencyKey(long userId);
    }

    public MqUserDeletedEventConsumerTemplate(ObjectMapper objectMapper, Cleanup cleanup) {
        this.objectMapper = objectMapper;
        this.cleanup = cleanup;
    }

    public void handleUserEvent(String message) {
        try {
            UserDeletedEvent event = objectMapper.readValue(message, UserDeletedEvent.class);
            String eventType = event.eventType();
            if (!RabbitMqConstants.ROUTING_KEY_USER_DELETED.equals(eventType)) {
                log.debug("MQ: 忽略非 user.deleted 用户事件: eventType={}", eventType);
                return;
            }

            long userId = event.userId();
            String username = event.username();

            if (!cleanup.tryAcquireIdempotencyKey(userId)) {
                // F4：区分「已真正完成」与「另一个消费者正在处理」，不要把两者都误报成「重复事件」——
                // 后者在上一消费者崩溃时只是短 TTL 未过期的暂时状态，误报会掩盖真实故障。
                if (cleanup.isCleanupCompleted(userId)) {
                    log.info("MQ: 用户删除事件已处理完成，跳过: userId={}", userId);
                } else {
                    log.warn("MQ: 用户删除事件正在处理中（并发或上次崩溃的短 TTL 未过期），本次跳过: userId={}", userId);
                }
                return;
            }

            try {
                log.info("MQ: 开始执行用户注销清理: userId={}, username={}", userId, username);
                cleanup.cleanup(userId);
                // 关键（F4）：清理**成功**后才写长 TTL 的 done 键；此前只有一个 24h 的「认领」键，
                // 进程若在清理完成前崩溃，键会残留 24 小时压制全部重投递，「半途而废」被误报成「重复事件」。
                cleanup.markCleanupCompleted(userId);
                log.info("MQ: 用户注销清理完成: userId={}, username={}", userId, username);
            } catch (Exception e) {
                cleanup.releaseIdempotencyKey(userId);
                log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, userId={}, message={}", userId, message, e);
                throw new RuntimeException("处理用户删除事件消息失败", e);
            }
        } catch (JsonProcessingException e) {
            log.error("用户删除事件消息反序列化失败（丢弃消息）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("用户删除事件消息反序列化失败", e);
        } catch (RuntimeException e) {
            // 业务失败已在内层记过 ERROR 并释放认领键，这里只透传给容器重试
            throw e;
        } catch (Exception e) {
            log.error("处理用户删除事件 RabbitMQ 消息失败（将重试）, message={}", message, e);
            throw new RuntimeException("处理用户删除事件消息失败", e);
        }
    }
}
