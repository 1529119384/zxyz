package uno.acloud.audit.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import uno.acloud.audit.config.RabbitMqConfig;

import java.util.Map;

@Slf4j
@Component
public class AuditDlqConsumer {

    @RabbitListener(queues = RabbitMqConfig.DLQ_QUEUE)
    public void handleDeadLetter(String message, @Headers Map<String, Object> headers) {
        String originalExchange = (String) headers.get("x-first-death-exchange");
        String originalQueue = (String) headers.get("x-first-death-queue");
        String reason = (String) headers.get("x-first-death-reason");
        log.error("[DLQ] 毒消息进入死信队列, originalExchange={}, originalQueue={}, reason={}, messageLength={}",
                originalExchange, originalQueue, reason, message == null ? 0 : message.length());
    }
}
