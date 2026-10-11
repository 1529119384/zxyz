package uno.acloud.im.infrastructure.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.event.FileResourceChangedEvent;
import uno.acloud.im.application.FileCardResolveCache;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link FileResourceChangedConsumer} 单测。
 *
 * <p>⚠️ 反序列化一律用<b>真实配置 ObjectMapper</b>（jsr310 JavaTimeModule 已注册、
 * 与发端序列化配置对齐），<b>不 mock</b>：全仓唯一含 {@code LocalDateTime} 字段的事件
 * {@link FileResourceChangedEvent#modifyTime} 正是本消费者的载荷 ——
 * 若 jsr310 缺失，这里的 round-trip 用例会响亮失败，而不是静默炸在线上。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileResourceChangedConsumerTest {

    @Mock
    private FileCardResolveCache fileCardResolveCache;

    private ObjectMapper objectMapper;
    private FileResourceChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        // 对齐发端 ObjectMapper：JavaTimeModule 注册 + 日期不写为时间戳（ISO-8601 文本）
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        consumer = new FileResourceChangedConsumer(objectMapper, fileCardResolveCache);
    }

    @Test
    void handle_validEventWithLocalDateTime_invalidatesCacheByFileId() {
        // LocalDateTime 走 ISO-8601 文本（WRITE_DATES_AS_TIMESTAMPS 关闭）——jsr310 缺失在此响亮失败
        String json = """
                {"eventType":"file.resource.changed","version":1,"timestamp":1745732712000,
                 "fileId":11,"parentId":-1,"storePath":"/p/a.txt","deleted":0,
                 "modifyTime":"2026-04-27T13:45:12","teamId":5,"projectId":null}
                """;

        consumer.handleMessage(json);

        verify(fileCardResolveCache).invalidateByFileId(11L);
    }

    @Test
    void handle_publishRoundTrip_keepsLocalDateTimePrecise() throws Exception {
        // 发端序列化 → 消费端反序列化 round-trip：LocalDateTime 必须逐字段保真
        FileResourceChangedEvent original = FileResourceChangedEvent.of(
                "file.resource.changed", 11L, -1L, "/p/a.txt", 0,
                LocalDateTime.of(2026, 4, 27, 13, 45, 12), 5L, null);

        String json = objectMapper.writeValueAsString(original);
        FileResourceChangedEvent parsed = objectMapper.readValue(json, FileResourceChangedEvent.class);

        assertEquals(original.modifyTime(), parsed.modifyTime());
        assertEquals(original.fileId(), parsed.fileId());
        assertEquals(original.teamId(), parsed.teamId());
    }

    @Test
    void handle_nullFileId_doesNotTouchCache() {
        String json = """
                {"eventType":"file.resource.changed","version":1,"timestamp":1745732712000,
                 "fileId":null,"parentId":-1,"storePath":"/p","deleted":0,
                 "modifyTime":"2026-04-27T13:45:12","teamId":5,"projectId":null}
                """;

        consumer.handleMessage(json);

        verify(fileCardResolveCache, never()).invalidateByFileId(anyLong());
        verifyNoInteractions(fileCardResolveCache);
    }

    @Test
    void handle_malformedJson_throwsAmqpRejectAndDontRequeue() {
        // 毒消息路径：反序列化失败 → AmqpRejectAndDontRequeueException（进 DLQ），绝不 log-and-return
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> consumer.handleMessage("{bad json"));

        verifyNoInteractions(fileCardResolveCache);
    }

    @Test
    void handle_wrongPayloadShape_throwsAmqpRejectAndDontRequeue() {
        // 形状对但类型不兼容（modifyTime 非时间格式）：同样是毒消息
        String json = """
                {"eventType":"file.resource.changed","version":1,"timestamp":1745732712000,
                 "fileId":11,"parentId":-1,"storePath":"/p","deleted":0,
                 "modifyTime":"not-a-date","teamId":5,"projectId":null}
                """;

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleMessage(json));
        verifyNoInteractions(fileCardResolveCache);
    }

    @Test
    void handle_cacheFailure_wrapsInRuntimeForRetry() {
        String json = """
                {"eventType":"file.resource.changed","version":1,"timestamp":1745732712000,
                 "fileId":11,"parentId":-1,"storePath":"/p","deleted":0,
                 "modifyTime":"2026-04-27T13:45:12","teamId":5,"projectId":null}
                """;
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(fileCardResolveCache).invalidateByFileId(11L);

        // 非 JSON 异常走「可重试」路径（普通 RuntimeException，触发 MQ 重试而非直接进 DLQ）
        RuntimeException ex = assertThrows(RuntimeException.class, () -> consumer.handleMessage(json));
        assertEquals("处理文件资源变更消息失败", ex.getMessage());
    }

    @Test
    void construct_withPlainObjectMapper_stillDeserializesIsoString() {
        // 无 JavaTimeModule 的裸 ObjectMapper：LocalDateTime 字段会炸 —— 这正是 A3 要暴露的回归面
        FileResourceChangedConsumer plain = new FileResourceChangedConsumer(new ObjectMapper(), fileCardResolveCache);
        String json = """
                {"eventType":"file.resource.changed","version":1,"timestamp":1745732712000,
                 "fileId":11,"parentId":-1,"storePath":"/p","deleted":0,
                 "modifyTime":"2026-04-27T13:45:12","teamId":5,"projectId":null}
                """;

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> plain.handleMessage(json));
    }
}
