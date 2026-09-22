package uno.acloud.project.mq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import uno.acloud.common.event.FileResourceChangedEvent;
import uno.acloud.project.service.impl.StorageQuotaCacheService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 锁住「定向缓存失效」这条链路（对应缺陷 C-4）。
 *
 * <p>背景：发布端 {@code FileResourceChangedEvent.of(...)} 曾有一个 6 参重载把 teamId 硬编码为 null，
 * 而 {@code FileResourceChangedPublisher.publishByIds} 恰好走它 ⇒ 事件里的 teamId 恒为 null
 * ⇒ 本消费端每次都走「全量失效」分支 ⇒ <b>定向失效从未生效过</b>。
 * 更糟的是解析失败被一个空的 {@code catch (Exception ignored) {}} 吞掉，所以生产上完全不可观测。</p>
 *
 * <p>本测试从<b>消费端</b>侧把契约钉住：teamId 有值时必须只失效该团队，
 * 载荷损坏时必须<b>响亮失败</b>而不是静默退化成全量失效。</p>
 */
@ExtendWith(MockitoExtension.class)
class StorageCacheInvalidationConsumerTest {

    private static final String MESSAGE = "{\"eventType\":\"CREATED\",\"fileId\":7}";

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private StorageQuotaCacheService cacheService;

    private StorageCacheInvalidationConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new StorageCacheInvalidationConsumer(objectMapper, cacheService);
    }

    @Test
    void teamIdPresent_invalidatesOnlyThatTeamCache() throws Exception {
        givenEventParsesTo(42L);

        consumer.handleFileEvent(MESSAGE);

        verify(cacheService).invalidateTeamCache(42L);
        verify(cacheService, never()).invalidateAllUsageCaches();
    }

    @Test
    void teamIdAbsent_fallsBackToFullInvalidation() throws Exception {
        givenEventParsesTo(null);

        consumer.handleFileEvent(MESSAGE);

        verify(cacheService).invalidateAllUsageCaches();
        verify(cacheService, never()).invalidateTeamCache(anyLong());
    }

    @Test
    void nonPositiveTeamId_fallsBackToFullInvalidation() throws Exception {
        givenEventParsesTo(0L);

        consumer.handleFileEvent(MESSAGE);

        verify(cacheService).invalidateAllUsageCaches();
        verify(cacheService, never()).invalidateTeamCache(anyLong());
    }

    @Test
    void malformedPayload_failsLoudlyInsteadOfSilentlyDegrading() throws Exception {
        when(objectMapper.readValue(MESSAGE, FileResourceChangedEvent.class))
                .thenThrow(mock(JsonProcessingException.class));

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.handleFileEvent(MESSAGE));

        // 🔴 本仓最关键的一条断言：修复前这里是一个空的 `catch (Exception ignored) {}`，
        //   随后会**静默**调用 invalidateAllUsageCaches()。若哪天有人把空 catch 改回来，
        //   这条断言会立刻变红 —— 而不是等到「线上缓存全量失效但无人知道」时才发现。
        verifyNoInteractions(cacheService);
    }

    private void givenEventParsesTo(Long teamId) throws Exception {
        FileResourceChangedEvent event = new FileResourceChangedEvent(
                "CREATED", 0, 0L, 7L, 3L, "/a/b", 0, null, teamId, null);
        when(objectMapper.readValue(MESSAGE, FileResourceChangedEvent.class)).thenReturn(event);
    }
}
