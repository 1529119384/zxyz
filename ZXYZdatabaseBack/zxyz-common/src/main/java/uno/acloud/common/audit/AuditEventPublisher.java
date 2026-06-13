package uno.acloud.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uno.acloud.common.RabbitMqConstants;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class AuditEventPublisher {

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int MAX_BUFFER_SIZE = 10_000;

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String fallbackPath;
    private final ConcurrentLinkedDeque<String> retryBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicInteger droppedCount = new AtomicInteger(0);
    private final RetryTemplate retryTemplate;

    public AuditEventPublisher(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper,
                               @Value("${audit.fallback.path:/var/log/audit-fallback.jsonl}") String fallbackPath) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.fallbackPath = fallbackPath;
        this.retryTemplate = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_RETRY_ATTEMPTS);
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(1000L);
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        retryTemplate.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.warn("发布审计事件失败，第{}次重试: {}", context.getRetryCount(), throwable.getMessage());
            }
        });
    }

    public void publish(OperateLog operateLog) {
        String json;
        try {
            json = objectMapper.writeValueAsString(operateLog);
        } catch (Exception e) {
            log.error("序列化审计事件失败: service={}", operateLog.getServiceName(), e);
            return;
        }
        try {
            retryTemplate.execute(context -> {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, RabbitMqConstants.ROUTING_KEY_AUDIT_LOG, json);
                log.debug("发布审计事件到 RabbitMQ: service={}, method={}", operateLog.getServiceName(), operateLog.getMethodName());
                return null;
            });
        } catch (Exception e) {
            log.error("发布审计事件失败（已重试{}次）: service={}, method={}", MAX_RETRY_ATTEMPTS, operateLog.getServiceName(), operateLog.getMethodName(), e);
            writeFallbackFile(json);
        }
    }

    /**
     * 重试缓冲区中的审计事件。由定时任务调用。
     *
     * @return 成功发布的事件数
     */
    public int retryBufferedEvents() {
        if (retryBuffer.isEmpty()) {
            return 0;
        }
        List<String> snapshot = new ArrayList<>(retryBuffer);
        retryBuffer.clear();

        int successCount = 0;
        List<String> stillFailed = new ArrayList<>();
        for (String json : snapshot) {
            try {
                rabbitTemplate.convertAndSend(RabbitMqConstants.EXCHANGE, RabbitMqConstants.ROUTING_KEY_AUDIT_LOG, json);
                successCount++;
            } catch (Exception e) {
                if (writeFallbackFileDirect(json)) {
                    log.debug("缓冲区审计事件已写入回退文件");
                } else {
                    stillFailed.add(json);
                }
            }
        }
        for (String json : stillFailed) {
            addToBuffer(json);
        }
        if (successCount > 0 || !stillFailed.isEmpty()) {
            log.info("审计事件缓冲区重试: 成功={}, 失败={}, 缓冲区剩余={}", successCount, stillFailed.size(), retryBuffer.size());
        }
        return successCount;
    }

    public int getBufferSize() {
        return retryBuffer.size();
    }

    void addToBufferForTest(String json) {
        addToBuffer(json);
    }

    private void writeFallbackFile(String json) {
        if (!writeFallbackFileDirect(json)) {
            addToBuffer(json);
        }
    }

    private boolean writeFallbackFileDirect(String json) {
        try {
            Path path = Paths.get(fallbackPath);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.warn("写入审计事件回退文件失败: path={}", fallbackPath, e);
            return false;
        }
    }

    private void addToBuffer(String json) {
        if (retryBuffer.size() >= MAX_BUFFER_SIZE) {
            retryBuffer.pollFirst();
            droppedCount.incrementAndGet();
            log.warn("审计事件缓冲区已满（{}条），丢弃最早事件，累计丢弃={}", MAX_BUFFER_SIZE, droppedCount.get());
        }
        retryBuffer.addLast(json);
        log.debug("审计事件已加入缓冲区: 缓冲区大小={}", retryBuffer.size());
    }
}
