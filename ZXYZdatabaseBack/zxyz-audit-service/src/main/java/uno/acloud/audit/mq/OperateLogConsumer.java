package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import uno.acloud.audit.config.RabbitMqConfig;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.common.audit.OperateLog;
import uno.acloud.common.audit.SensitiveDataMasker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class OperateLogConsumer {

    /** 幂等命中的计数器名（审计 L7：重复投递必须可观测，不能只有一行 WARN）。 */
    static final String METRIC_DUPLICATE_MESSAGES = "audit.duplicate.messages";

    /** 运行时缺 jsr310 的降级旗标：=1 时审计不可用，Prometheus 告警应立刻可见。 */
    static final String METRIC_MISSING_JSR310 = "audit.mapper.missing.jsr310";
    /** gauge 取值用的旗标常量（缺失=1 / 正常=0），避免魔法数字散落。 */
    static final long FLAG_MISSING = 1L;
    static final long FLAG_OK = 0L;

    private final OperateLogMapper operateLogMapper;
    private final ObjectMapper objectMapper;
    private final Counter duplicateMessageCounter;
    /** jsr310 缺失旗标的可变持有者：构造器自检后由 gauge 读出，缺失时拒绝消费。 */
    private final AtomicLong missingJsr310 = new AtomicLong(FLAG_OK);

    public OperateLogConsumer(OperateLogMapper operateLogMapper, ObjectMapper objectMapper,
                              MeterRegistry meterRegistry) {
        this.operateLogMapper = operateLogMapper;
        this.objectMapper = objectMapper;
        // 计数器在构造期注册一次：Micrometer 的 Counter 是单调累加的，重复 register 会返回同一实例。
        this.duplicateMessageCounter = Counter.builder(METRIC_DUPLICATE_MESSAGES)
                .description("重复投递（DB 唯一键命中）而被跳过的审计日志消息数")
                .register(meterRegistry);
        Gauge.builder(METRIC_MISSING_JSR310, missingJsr310, AtomicLong::doubleValue)
                .description("1=运行时缺 jackson-datatype-jsr310，OperateLog 无法反序列化，审计静默丢失中")
                .register(meterRegistry);
        // P0（ISSUE/51）启动自检：Boot 4 迁移后 audit 曾因 jsr310 仅 test scope，
        // 运行时 ObjectMapper 未注册 JavaTimeModule，readValue(OperateLog) 全量抛
        // InvalidDefinitionException 且被 catch 吞掉 —— 审计静默丢失 17 小时。
        // ⚠️ 不能用 canDeserialize(OperateLog.class)：它只做根类型解析器查找，POJO 层面
        // 恒为 true，探测不到 LocalDateTime 属性级的 InvalidDefinitionException（已实测）。
        // 这里用真实探针：对注入的 mapper 反序列化一个含 operateTime 的样本，命中的正是
        // 生产故障路径。失败则打 ERROR 并置降级旗标（audit.mapper.missing.jsr310=1），
        // 让同类问题 5 分钟内可在监控看见。
        // 注意：不自行 new ObjectMapper 替换 bean（那会偏离 Spring 统一配置），只自检 + 可观测。
        boolean canDeserializeOperateLog;
        try {
            canDeserializeOperateLog = objectMapper
                    .readValue("{\"operateTime\":\"2026-01-01T00:00:00\"}", OperateLog.class) != null;
        } catch (JsonProcessingException probeFailure) {
            canDeserializeOperateLog = false;
        }
        if (!canDeserializeOperateLog) {
            missingJsr310.set((long) FLAG_MISSING);
            log.error("审计链路启动自检失败：注入的 ObjectMapper 无法反序列化 OperateLog（大概率运行时缺 "
                            + "jackson-datatype-jsr310，JavaTimeModule 未注册）。所有审计消息将被拒绝送入死信队列 "
                            + "audit.dlq（业务可恢复），但审计持续丢失 —— 请立刻检查本模块 pom 的 jsr310 依赖 scope。"
                            + "降级指标 {}=1 已置位，消费已停摆。", METRIC_MISSING_JSR310);
        } else {
            log.info("审计链路启动自检通过：ObjectMapper 可反序列化 OperateLog（LocalDateTime 支持 OK）");
        }
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_AUDIT_OPERATE_LOG)
    public void handleAuditLog(String message) {
        if (missingJsr310.get() == (long) FLAG_MISSING) {
            // 反序列化必然失败（InvalidDefinitionException），重试也救不回来：
            // 直接拒绝不重投，经 DLX 落入 audit.dlq（该队列无 TTL，消息保留），
            // 等 jsr310 修复上线后可从死信回放；比起每条消息刷一遍 InvalidDefinitionException 堆栈，
            // 这里用一行 ERROR + 旗标定位问题。
            log.error("运行时缺 jsr310（{}=1），审计消息拒绝消费，落入死信队列待修复后回放。messageLength={}",
                    METRIC_MISSING_JSR310, message.length());
            throw new AmqpRejectAndDontRequeueException("运行时缺 jsr310，审计消息转入死信队列");
        }
        try {
            OperateLog operateLog = objectMapper.readValue(message, OperateLog.class);
            // 第二道防线：发布端切面已脱敏，但切面可能因参数形态不匹配而漏脱敏
            // （如非 JSON 字符串、Lombok toString 未 exclude 的敏感字段）。
            // 落库前再跑一次同一份正则 —— 入库是持久化泄露，代价远高于这次 O(n) 扫描。
            redactSensitiveFields(operateLog);
            // 幂等下沉到 DB 唯一约束 unique(message_hash)：直接插入，命中唯一键冲突视为重复消息跳过（ACK）。
            // 不再依赖 Redis 先占位后插入（先占位在 insert 失败时会阻断重投，导致审计日志永久丢失）。
            int rows = operateLogMapper.insertWithHash(operateLog, sha256Hex(message));
            if (rows != 1) {
                // 防御性兜底：唯一键冲突由异常承载，理论上不会走到这里；
                // 一旦发生说明「写了但没写进去」，不能静默当成功。
                log.warn("审计日志写入行数异常: rows={}, service={}, method={}",
                        rows, operateLog.getServiceName(), operateLog.getMethodName());
            }
            log.debug("审计日志写入完成: service={}, method={}", operateLog.getServiceName(), operateLog.getMethodName());
        } catch (DuplicateKeyException e) {
            // 唯一键冲突（message_hash 已入库）＝同一消息重复投递，正常跳过并 ACK，不重投不进 DLQ。
            // 但「跳过」本身必须是可观测的：重复投递意味着投递系统已偏离 exactly-once 假设，
            // 运维需要能据此发现生产者补偿抖动 / 消费者重平衡，故计数 + ERROR 级日志双留痕。
            duplicateMessageCounter.increment();
            log.error("MQ: 重复审计日志消息，跳过处理（DB 唯一键命中）, messageLength={}, duplicateCount={}",
                    message.length(), duplicateMessageCounter.count());
        } catch (JsonProcessingException e) {
            // Poison message: deserialization will never succeed on retry, send to DLQ
            log.error("审计日志反序列化失败（送入死信队列）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("审计日志反序列化失败", e);
        } catch (Exception e) {
            // 真正的持久化失败（连接异常等）不吞，抛出以触发重投/DLQ。
            log.error("审计日志写入失败（将重试）, message={}", message, e);
            throw new RuntimeException("审计日志写入失败", e);
        }
    }

    /**
     * 对即将落库的审计文本做敏感字段打码（就地修改）。
     * <p>与发布端共用 {@link SensitiveDataMasker}，保证两端口径一致。</p>
     */
    private static void redactSensitiveFields(OperateLog operateLog) {
        operateLog.setMethodParams(SensitiveDataMasker.mask(operateLog.getMethodParams()));
        operateLog.setReturnValue(SensitiveDataMasker.mask(operateLog.getReturnValue()));
        operateLog.setBeforeValue(SensitiveDataMasker.mask(operateLog.getBeforeValue()));
        operateLog.setAfterValue(SensitiveDataMasker.mask(operateLog.getAfterValue()));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
