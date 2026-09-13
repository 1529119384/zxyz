package uno.acloud.audit.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import uno.acloud.audit.config.RabbitMqConfig;
import uno.acloud.common.mq.MqMessageDiagnostics;

import java.util.Map;

/**
 * 审计服务死信消费者。
 *
 * <p>审计 L7 的口径是<strong>不加死信表、只保留 ERROR 日志</strong>，因此这条 ERROR 就是
 * 毒消息最终的现场记录。原实现只打了 {@code messageLength}，缺 service / queue / 摘要 /
 * 次数 —— 出事时无法定位是哪条队列、哪条消息、死了几次。本次按该口径补齐上下文。</p>
 *
 * <p><b>为什么可以打印 payload 预览</b>：本服务的业务消费者 {@code OperateLogConsumer}
 * 在其失败分支里本就打印 {@code message={}} 全文（见该类 catch 块），故此处补一个
 * <strong>清洗 + 截断</strong>后的预览并不引入新的暴露面，反而是收敛。此外审计消息本身
 * 带 {@code message_hash}，摘要（{@code payloadSha256_12}）可与库内已有的 hash 直接对齐。</p>
 */
@Slf4j
@Component
public class AuditDlqConsumer {

    private static final String SERVICE = "audit-service";

    @RabbitListener(queues = RabbitMqConfig.DLQ_QUEUE)
    public void handleDeadLetter(String message, @Headers Map<String, Object> headers) {
        log.error("[DLQ] 毒消息进入死信队列: service={}, dlqQueue={}, payloadLength={}, "
                        + "payloadSha256_12={}, payloadPreview={}, death={}",
                SERVICE, RabbitMqConfig.DLQ_QUEUE,
                MqMessageDiagnostics.lengthOf(message),
                MqMessageDiagnostics.digest(message),
                MqMessageDiagnostics.preview(message),
                MqMessageDiagnostics.describeDeath(headers));
    }
}
