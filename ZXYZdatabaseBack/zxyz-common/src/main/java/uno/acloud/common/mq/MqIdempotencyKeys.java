package uno.acloud.common.mq;

import org.slf4j.Logger;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * MQ 幂等占位键的「失败释放」公共实现。
 *
 * <p><b>为什么必须集中</b>：各消费者统一采用「先 {@code setIfAbsent} 占位、处理成功后自然过期、
 * 处理失败则释放占位」的模式。释放这一步若写成「直接 delete 且不兜异常」，
 * Redis 抖动时抛出的异常会<b>盖掉原始业务异常</b>——现场日志从此只看到 Redis 报错，
 * 真正的失败原因（DB 约束、下游 500）被吞掉。本仓 im-service 的两份实现
 * （{@code UserEventConsumer}/{@code TeamEventConsumer}）与 share-service 的一份
 * 此前是逐字复制的同一段 try-delete-双分支日志，任一处被改错都只在故障路径下暴露。</p>
 *
 * <p><b>调用约定</b>：只在「占位已完成、业务处理抛异常」的 catch 里调用。
 * 不要在占位之前调用（那会误删别人的占位），也不要在成功后调用
 * （成功后应让 TTL 自然过期，否则重复投递会被重复处理）。</p>
 */
public final class MqIdempotencyKeys {

    private MqIdempotencyKeys() {
    }

    /**
     * 释放幂等占位键，使失败消息在 MQ 重投时能被真正重新处理。
     *
     * <p>Redis 自身异常只记日志、<b>不抛出</b>：本方法被调用的位置已经在异常处理路径上，
     * 再抛异常会掩盖原始业务异常，让排查从「下游为什么失败」偏移成「Redis 为什么失败」。
     * 释放失败时记 ERROR，因为它有明确的数据后果 —— 该消息重投会被判为重复而跳过。</p>
     *
     * @param redisTemplate Redis 模板
     * @param idempotencyKey 待释放的幂等键（键名自带域前缀，如 {@code mq:idempotent:user:...}）
     * @param log 调用方 logger（保留各服务的 logger 名，便于按服务过滤日志）
     */
    public static void release(StringRedisTemplate redisTemplate, String idempotencyKey, Logger log) {
        try {
            redisTemplate.delete(idempotencyKey);
            log.warn("MQ: 事件处理失败，已释放幂等占位键以便重投重试: key={}", idempotencyKey);
        } catch (Exception e) {
            log.error("MQ: 释放幂等占位键失败（该消息重投将被判为重复而跳过）: key={}", idempotencyKey, e);
        }
    }
}
