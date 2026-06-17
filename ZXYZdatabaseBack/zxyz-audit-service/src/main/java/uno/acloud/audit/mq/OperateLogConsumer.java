package uno.acloud.audit.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uno.acloud.audit.config.RabbitMqConfig;
import uno.acloud.audit.mapper.OperateLogMapper;
import uno.acloud.common.audit.OperateLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class OperateLogConsumer {

    private static final String IDEMPOTENCY_KEY_PREFIX = "mq:idempotent:audit:";
    private static final long IDEMPOTENCY_TTL_HOURS = 1;

    private final OperateLogMapper operateLogMapper;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    public OperateLogConsumer(OperateLogMapper operateLogMapper, ObjectMapper objectMapper, StringRedisTemplate redisTemplate) {
        this.operateLogMapper = operateLogMapper;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
    }

    @RabbitListener(queues = RabbitMqConfig.QUEUE_AUDIT_OPERATE_LOG)
    public void handleAuditLog(String message) {
        try {
            OperateLog operateLog = objectMapper.readValue(message, OperateLog.class);
            String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + sha256Hex(message);
            if (!redisTemplate.opsForValue().setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL_HOURS, TimeUnit.HOURS)) {
                log.warn("MQ: 重复审计日志消息，跳过处理: key={}", idempotencyKey);
                return;
            }
            operateLogMapper.insert(operateLog);
            log.debug("审计日志写入完成: service={}, method={}", operateLog.getServiceName(), operateLog.getMethodName());
        } catch (JsonProcessingException e) {
            // Poison message: deserialization will never succeed on retry, send to DLQ
            log.error("审计日志反序列化失败（送入死信队列）, message={}", message, e);
            throw new AmqpRejectAndDontRequeueException("审计日志反序列化失败", e);
        } catch (Exception e) {
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
