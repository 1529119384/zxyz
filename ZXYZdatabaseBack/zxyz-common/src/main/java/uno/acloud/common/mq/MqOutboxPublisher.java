package uno.acloud.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 领域事件 Redis outbox：afterCommit 发布失败的「重试载体」（60 号审计 🟠-2 最小版）。
 *
 * <h2>为什么需要它</h2>
 * <p>领域事件（team.created / user.deleted 等）的既有链路是「DB 提交成功 → afterCommit 发 MQ
 * → 失败只 log.error」（{@code TransactionUtils.safeRun} 吞异常）。RabbitMQ 抖动超过
 * {@link MqRetryTemplateFactory} 的 3 次重试窗口（约 3 秒）后，事件<b>永久丢失</b>：
 * team.created 丢失 ⇒ im 永远没有该团队投影；user.deleted 丢失 ⇒ 五个下游的注销清理全部跳过。
 * 恢复手段此前只有人工翻 ERROR 日志。</p>
 *
 * <h2>设计决策：Redis outbox 而不是 DB outbox 表</h2>
 * <ul>
 *   <li><b>写入时机</b>：只在 MQ 发布<b>重试耗尽后</b>写入（不是发布前）——正常路径零额外 I/O，
 *       outbox 只承载失败尾部，键量与失败率同阶（而非与业务量同阶）；</li>
 *   <li><b>载体</b>：Redis 键 {@code mq:outbox:<service>:<eventType>:<uuid>}，TTL 24h
 *       （与消费端 DLQ 的 message-ttl 同量级），值 = 事件 JSON；</li>
 *   <li><b>补偿</b>：{@link MqOutboxRetryScheduler} 周期扫描本服务的 outbox 键重发，成功即删；</li>
 *   <li><b>残余风险（已知、登记）</b>：Redis 与 RabbitMQ 同时不可用、且服务实例在 outbox 落盘前
 *       宕机 ⇒ 事件仍会丢。这是「最小版」接受的边界——彻底解法是 DB outbox 表（与业务同事务写入
 *       + 独立补偿器），涉及 DDL 与新消费者，超出本批次改动面，已登记为后续项；</li>
 *   <li><b>重发语义</b>：outbox 重发的是<b>同一份 JSON</b>，消费端本就按幂等键去重
 *       （user.deleted 三件套 / team 幂等键 + 序列号），重复投递安全；</li>
 *   <li><b>幂等重发自身</b>：重发成功后先发 MQ 再删键，两步之间崩溃最多导致一次重复投递
 *       （仍被消费端幂等拦截），不会丢事件 —— 与「宁可多跑不可漏发」取舍一致。</li>
 * </ul>
 *
 * <p><b>为什么放 common</b>：team 与 user 两个发布方复用同一实现，扫描/重发/删除的口径
 * （键格式、TTL、批量上限）只维护一份。</p>
 */
@Slf4j
public class MqOutboxPublisher {

    /** outbox 键前缀（后接 {@code <service>:<eventType>:<uuid>}）。 */
    public static final String KEY_PREFIX = "mq:outbox:";

    /** outbox 键 TTL：与消费端 DLQ 的 message-ttl（24h）同量级，过期即认为事件已无人接手。 */
    public static final Duration TTL = Duration.ofHours(24);

    /** 单次扫描重发的键数上限：防止失败风暴时一次性占满调度线程。 */
    static final int MAX_RETRY_BATCH = 100;

    private final StringRedisTemplate redisTemplate;
    private final String serviceName;

    public MqOutboxPublisher(StringRedisTemplate redisTemplate, String serviceName) {
        this.redisTemplate = redisTemplate;
        this.serviceName = serviceName;
    }

    /**
     * MQ 发布重试耗尽后调用：把事件 JSON 存入本服务的 outbox，等待定时补偿重发。
     * <p><b>Redis 不可用时只记日志不抛出</b>：本方法运行在 afterCommit 失败路径上，
     * 再抛异常既无法回滚已提交的事务，又会污染 {@code TransactionUtils} 的失败日志。
     * Redis 与 RabbitMQ 同时不可用属于登记过的残余风险（见类注释）。</p>
     *
     * @return true = 已落 outbox；false = Redis 不可用，事件丢失（仅剩 ERROR 日志可循）
     */
    public boolean enqueue(String eventType, String eventJson) {
        String key = KEY_PREFIX + serviceName + ":" + eventType + ":" + java.util.UUID.randomUUID();
        try {
            redisTemplate.opsForValue().set(key, eventJson, TTL);
            log.warn("领域事件发布失败已落入 outbox 等待补偿重发: key={}", key);
            return true;
        } catch (Exception e) {
            log.error("领域事件写入 outbox 失败（Redis 不可用，事件丢失，需人工按日志补偿）: eventType={}, key={}",
                    eventType, key, e);
            return false;
        }
    }

    /**
     * 扫描本服务全部 outbox 键并重发；成功即删键，失败保留等下一轮。
     * 由 {@link MqOutboxRetryScheduler} 周期调用；也可在发布方发布前调用作为「启动清欠」。
     *
     * @return 成功重发并删除的事件数
     */
    public int retryPending() {
        List<String> keys = scanKeys();
        int success = 0;
        for (String key : keys) {
            if (retryOne(key)) {
                success++;
            }
        }
        if (!keys.isEmpty()) {
            log.info("outbox 补偿重发完成: service={}, 扫描={}, 成功={}, 失败={}",
                    serviceName, keys.size(), success, keys.size() - success);
        }
        return success;
    }

    /** 扫描本服务的 outbox 键（只取本服务前缀，互不干扰）。 */
    private List<String> scanKeys() {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(KEY_PREFIX + serviceName + ":*")
                .count(200)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext() && keys.size() < MAX_RETRY_BATCH) {
                keys.add(cursor.next());
            }
        } catch (Exception e) {
            log.warn("outbox 扫描失败（Redis 不可用，本轮跳过）: service={}", serviceName, e);
            return List.of();
        }
        return keys;
    }

    /**
     * 重发单条：先发 MQ、成功后删键。发送本身不带业务重试（MQ 不可用立刻失败），
     * 下一个调度周期会再次尝试 —— 频率控制交给调度器。
     */
    private boolean retryOne(String key) {
        String eventJson = redisTemplate.opsForValue().get(key);
        if (eventJson == null) {
            // TTL 已过或已被并发实例处理，无需动作
            return false;
        }
        String eventType = eventTypeOf(key, serviceName);
        try {
            rabbitTemplate().convertAndSend(uno.acloud.common.RabbitMqConstants.EXCHANGE, eventType, eventJson);
            redisTemplate.delete(key);
            log.info("outbox 事件补偿重发成功: key={}", key);
            return true;
        } catch (Exception e) {
            log.warn("outbox 事件补偿重发失败（保留等下一轮）: key={}", key, e);
            return false;
        }
    }

    /** 从键名还原事件类型：{@code mq:outbox:<service>:<eventType...>:<uuid>}（eventType 自身可含点号）。 */
    static String eventTypeOf(String key, String service) {
        String withoutPrefix = key.substring(KEY_PREFIX.length() + service.length() + 1);
        int lastColon = withoutPrefix.lastIndexOf(':');
        return lastColon > 0 ? withoutPrefix.substring(0, lastColon) : withoutPrefix;
    }

    /**
     * RabbitTemplate 由子类上下文注入 —— 本类放 common 但 common 不声明 AMQP Bean，
     * 通过构造注入会强迫所有引入方都提供模板；改为可覆盖的惰性获取，
     * 让只有真正启用 outbox 的服务（team / user）承担装配。
     */
    protected RabbitTemplate rabbitTemplate() {
        throw new IllegalStateException("启用 outbox 的服务必须覆写 rabbitTemplate() 提供发布通道");
    }

    /** 供测试/运维读取服务名。 */
    public String serviceName() {
        return serviceName;
    }

    /**
     * 序列化辅助：事件对象 → JSON；序列化失败返回 null（调用方按「未落 outbox」处理）。
     * 事件 record 理论上不会序列化失败，但 outbox 写入点在失败路径上，防御性处理。
     */
    public String toJson(Object event, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("outbox 事件序列化失败（事件丢失，需人工补偿）: event={}", event, e);
            return null;
        }
    }
}
