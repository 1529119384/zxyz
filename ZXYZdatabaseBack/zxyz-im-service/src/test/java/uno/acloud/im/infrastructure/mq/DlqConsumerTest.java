package uno.acloud.im.infrastructure.mq;

import org.junit.jupiter.api.Test;
import uno.acloud.common.mq.MqMessageDiagnostics;
import uno.acloud.im.config.RabbitMqConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * {@link DlqConsumer} 单测。
 *
 * <p>DLQ 消费者是「毒消息最终现场」的记录者（审计 L7 口径：不加死信表、只留 ERROR 日志），
 * 它绝不能因任何畸形头/畸形 payload 而抛异常 —— 消费死信再抛异常会把毒消息送进无限循环。
 * 本类用真实死信头形态（x-death 列表结构）验证消费不炸。</p>
 */
class DlqConsumerTest {

    private final DlqConsumer consumer = new DlqConsumer();

    @Test
    void handleDeadLetter_withFullDeathHeaders_doesNotThrow() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-first-death-queue", RabbitMqConfig.QUEUE);
        headers.put("x-first-death-reason", "rejected");
        headers.put("x-first-death-exchange", "zxyz.topic");
        headers.put("x-death", List.of(
                Map.of("queue", RabbitMqConfig.QUEUE, "reason", "rejected", "count", 3L)
        ));

        assertDoesNotThrow(() -> consumer.handleDeadLetter("{\"poison\":true", headers));
    }

    @Test
    void handleDeadLetter_withEmptyHeaders_doesNotThrow() {
        assertDoesNotThrow(() -> consumer.handleDeadLetter("any payload", new HashMap<>()));
    }

    @Test
    void handleDeadLetter_withNullPayload_doesNotThrow() {
        assertDoesNotThrow(() -> consumer.handleDeadLetter(null, null));
    }

    @Test
    void handleDeadLetter_withHostileControlChars_doesNotThrow() {
        // log injection 尝试（换行伪造日志行）：preview 层清洗，消费不得炸
        assertDoesNotThrow(() -> consumer.handleDeadLetter("line1\r\nline2\u0000Injected: ERROR", null));
    }

    @Test
    void handleDeadLetter_withMalformedXDeath_doesNotThrow() {
        // x-death 结构损坏（非预期形态）也必须被防御性吞掉
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-death", "not-a-list");
        headers.put("x-first-death-queue", 42);

        assertDoesNotThrow(() -> consumer.handleDeadLetter("payload", headers));
    }

    @Test
    void mqMessageDiagnostics_describeDeath_sumsCountsAcrossEntries() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-death", List.of(
                Map.of("queue", "q1", "reason", "rejected", "count", 2L),
                Map.of("queue", "q2", "reason", "expired", "count", 5L)
        ));

        String description = MqMessageDiagnostics.describeDeath(headers);

        assertDoesNotThrow(() -> {
            if (!description.contains("deathCount=7")) {
                throw new AssertionError("死亡次数应跨条目累计：实际 " + description);
            }
        });
    }
}
