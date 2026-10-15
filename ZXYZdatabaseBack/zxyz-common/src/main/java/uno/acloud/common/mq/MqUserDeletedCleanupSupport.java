package uno.acloud.common.mq;

import org.slf4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * user.deleted 幂等三件套（认领键 + done 键 + 失败释放）的公共实现。
 *
 * <p><b>为什么必须下沉</b>：本仓五处 {@code user.deleted} 消费端的幂等键管理此前各写各的
 * ——file-service 已修「半途而废」缺陷（60 号审计 🟠-4 / F4），project / share / team 三处
 * 仍是裸的 24h 占位键。旧实现的致命窗口是：{@code setIfAbsent} 成功后进程在清理完成前崩溃，
 * 键会残留整整 24 小时，期间所有 MQ 重投递都命中「重复事件，跳过」⇒ 该用户的清理被
 * <b>永久压制</b>，且日志把「半途而废」误报成「重复事件」。三处未同步正是「一处修了、
 * 五处没修」结构性的直接产物，故把 file 版已被真实缺陷证明的三件套下沉为唯一实现。</p>
 *
 * <h2>两段式键的语义</h2>
 * <ul>
 *   <li>{@code <prefix>:processing:<id>} —— <b>短</b> TTL（默认 {@value #PROCESSING_TTL_MINUTES}
 *       分钟），表示「本次清理正在进行」。进程在清理完成前崩溃时，它会在短 TTL 后自然过期，
 *       后续重投递得以重新接管执行；</li>
 *   <li>{@code <prefix>:done:<id>} —— <b>长</b> TTL（默认 {@value #DONE_TTL_HOURS} 小时），
 *       只在清理<b>真正成功</b>后写入，负责把重复投递拦在真正的「已完成」语义上。</li>
 * </ul>
 *
 * <p>崩溃场景的恢复窗口从「最长 24 小时」缩短到 {@value #PROCESSING_TTL_MINUTES} 分钟，
 * 而「真正已完成」的语义仍由长 TTL 的 done 键守住。键前缀沿用各服务既有形态
 * （{@code mq:idempotent:user:deleted:<svc>:}），Redis 库号隔离不变，历史键自然过期，无需迁移。</p>
 *
 * <p><b>与 {@link MqIdempotencyKeys#release} 的关系</b>：失败释放路径复用既有公共实现，
 * 保证「Redis 异常不掩盖原始业务异常」的语义在同一处维护。</p>
 */
public final class MqUserDeletedCleanupSupport {

    /** 处理中键的短 TTL（分钟）：崩溃后最多这么久就能被重投递接管。 */
    public static final long PROCESSING_TTL_MINUTES = 10;

    /** 已完成键的长 TTL（小时）：真正的重复投递在此窗口内被跳过。 */
    public static final long DONE_TTL_HOURS = 24;

    private MqUserDeletedCleanupSupport() {
    }

    /**
     * 认领本次清理。返回 {@code false} 有两种成因，语义都是「本次不要再跑」：
     * <ol>
     *   <li>{@code done} 键存在 ⇒ 该用户的清理<b>已真正完成</b>（正常的重复投递）；</li>
     *   <li>{@code processing} 键仍在 ⇒ 另一个消费者正在处理（并发投递），或上一个消费者
     *       崩溃后短 TTL 尚未过期 —— 后者最多 {@value #PROCESSING_TTL_MINUTES} 分钟后即可重试。</li>
     * </ol>
     * 调用方应先用 {@link #isCleanupCompleted} 区分两种成因，日志口径才能准确
     * （file 版的教训：把「半途而废」误报成「重复事件」会掩盖真实故障）。
     *
     * @return {@code true} 表示认领成功，调用方必须保证最终走到 {@link #markCleanupCompleted}
     *         或 {@link #releaseIdempotencyKey} 之一
     */
    public static boolean tryAcquireIdempotencyKey(StringRedisTemplate redisTemplate,
                                                   String processingKeyPrefix,
                                                   String doneKeyPrefix,
                                                   long businessId) {
        if (isCleanupCompleted(redisTemplate, doneKeyPrefix, businessId)) {
            return false;
        }
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(processingKeyPrefix + businessId, "1",
                        PROCESSING_TTL_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 清理<b>成功</b>后调用：写入长 TTL 的 done 键并释放 processing 键。
     * <p>必须先写 done 再删 processing：反过来的话，两步之间崩溃会让两个键都不存在，
     * 重复投递会重新执行一遍清理（虽然幂等，但白跑一次）。</p>
     */
    public static void markCleanupCompleted(StringRedisTemplate redisTemplate,
                                            String processingKeyPrefix,
                                            String doneKeyPrefix,
                                            long businessId) {
        redisTemplate.opsForValue().set(doneKeyPrefix + businessId, "1",
                DONE_TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.delete(processingKeyPrefix + businessId);
    }

    /** 该业务标识的清理是否已真正完成（供消费者区分「已完成」与「处理中」以便日志准确）。 */
    public static boolean isCleanupCompleted(StringRedisTemplate redisTemplate,
                                             String doneKeyPrefix,
                                             long businessId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(doneKeyPrefix + businessId));
    }

    /**
     * 释放「处理中」认领（失败回滚路径）。委托 {@link MqIdempotencyKeys#release}：
     * Redis 异常只记日志不抛出，避免盖掉调用方 catch 块里的原始业务异常。
     */
    public static void releaseIdempotencyKey(StringRedisTemplate redisTemplate,
                                             String processingKeyPrefix,
                                             long businessId,
                                             Logger callerLog) {
        MqIdempotencyKeys.release(redisTemplate, processingKeyPrefix + businessId, callerLog);
    }
}
