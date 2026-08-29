package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import uno.acloud.audit.config.RabbitMqConfig;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.common.audit.OperateLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Component
public class OperateLogConsumer {

    private final OperateLogMapper operateLogMapper;
    private final ObjectMapper objectMapper;

    public OperateLogConsumer(OperateLogMapper operateLogMapper, ObjectMapper objectMapper) {
        this.operateLogMapper = operateLogMapper;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_AUDIT_OPERATE_LOG)
    public void handleAuditLog(String message) {
        try {
            OperateLog operateLog = objectMapper.readValue(message, OperateLog.class);
            // 幂等下沉到 DB 唯一约束 unique(message_hash)：直接插入，命中唯一键冲突视为重复消息跳过（ACK）。
            // 不再依赖 Redis 先占位后插入（先占位在 insert 失败时会阻断重投，导致审计日志永久丢失）。
            operateLogMapper.insertWithHash(operateLog, sha256Hex(message));
            log.debug("审计日志写入完成: service={}, method={}", operateLog.getServiceName(), operateLog.getMethodName());
        } catch (DuplicateKeyException e) {
            // 唯一键冲突（message_hash 已入库）＝同一消息重复投递，正常跳过并 ACK，不重投不进 DLQ。
            log.warn("MQ: 重复审计日志消息，跳过处理（DB 唯一键命中）, messageLength={}", message.length());
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