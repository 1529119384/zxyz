package uno.acloud.im.infrastructure.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import uno.acloud.common.mq.MqMessageDiagnostics;
import uno.acloud.im.config.RabbitMqConfig;

import java.util.Map;

/**
 * IM 服务死信消费者。
 *
 * <p>消息能走到这里，说明它已经过「业务队列 → 消费重试耗尽（{@code application-common.yml}
 * 的 {@code listener.simple.retry}，默认 3 次）→ reject 不回队 → {@code x-dead-letter-exchange:
 * zxyz.dlx}」的完整链路。审计 L7 的口径是<strong>不加死信表、只保留 ERROR 日志</strong>，
 * 所以这条 ERROR 就是该消息最终的现场记录。</p>
 *
 * <p><b>为什么不再打印 payload 全文</b>：原实现是 {@code message={}}，把用户可控内容原样写进
 * 日志 —— 既有 log injection 风险（换行即可伪造日志行），也会灌爆日志文件。改为三者并存：</p>
 * <ul>
 *   <li>{@code payloadSha256_12}：用于跨日志比对/去重（同一条毒消息重复出现时一眼认出）；</li>
 *   <li>{@code payloadPreview}：清洗 + 截断后的前 {@value MqMessageDiagnostics#PREVIEW_LIMIT} 字符，
 *       用于人眼判断现场 —— 不加死信表就没有别的地方能看，只给 hash 等于不可读；</li>
 *   <li>{@code payloadLength}：预览会被截断，长度才是「到底多大」的判据。</li>
 * </ul>
 *
 * <p>上下文按审计 L7 要求对齐：<strong>service / queue / payload 摘要 / 死亡次数</strong>
 * （原始队列与原因来自死信头，见 {@link MqMessageDiagnostics#describeDeath}）。</p>
 */
@Slf4j
@Component
public class DlqConsumer {

    private static final String SERVICE = "im-service";

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
