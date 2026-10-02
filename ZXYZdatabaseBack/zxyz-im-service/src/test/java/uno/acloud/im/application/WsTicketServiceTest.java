package uno.acloud.im.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import uno.acloud.im.config.ImProperties;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link WsTicketService} 一次性票据的<b>消费时序</b>测试（B-5）。
 *
 * <p>要守住的不变量：<b>票据不能被「校验失败」的握手白白吃掉</b>。
 * 旧实现直接 GETDEL 再解析，解析一失败票据就已从 Redis 消失，
 * prod（禁 token fallback）下客户端会拿到「票据有效却被 401、且票据已毁」的死状态。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WsTicketServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WsTicketService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ImProperties imProperties = new ImProperties();
        imProperties.getWs().setTicketTtlSeconds(30);
        service = new WsTicketService(redisTemplate, objectMapper, imProperties);
    }

    // ==================== 正常路径 ====================

    @Test
    void resolveAndConsumeTicket_validTicket_returnsInfoAndConsumes() {
        when(valueOperations.get("ws:ticket:t-1"))
                .thenReturn("{\"userId\":7,\"saToken\":\"token-abc\"}");
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn("consumed");

        Optional<WsTicketService.TicketInfo> result = service.resolveAndConsumeTicket("t-1");

        assertTrue(result.isPresent());
        assertEquals(7L, result.get().userId());
        assertEquals("token-abc", result.get().saToken());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("ws:ticket:t-1")));
    }

    // ==================== B-5 核心：解析失败不得消费票据 ====================

    @Test
    void resolveAndConsumeTicket_malformedJson_doesNotConsumeTicket() {
        // 坏值（Redis 读到半截 / 序列化形态变更）
        when(valueOperations.get("ws:ticket:bad"))
                .thenReturn("{not-valid-json");

        Optional<WsTicketService.TicketInfo> result = service.resolveAndConsumeTicket("bad");

        assertFalse(result.isPresent(), "解析失败应返回空");
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    void resolveAndConsumeTicket_redisReadFailure_doesNotConsumeTicket() {
        when(valueOperations.get("ws:ticket:flaky"))
                .thenThrow(new RuntimeException("Redis connection reset"));

        Optional<WsTicketService.TicketInfo> result = service.resolveAndConsumeTicket("flaky");

        assertFalse(result.isPresent());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    void resolveAndConsumeTicket_missingTicket_doesNotConsume() {
        when(valueOperations.get("ws:ticket:gone")).thenReturn(null);

        assertFalse(service.resolveAndConsumeTicket("gone").isPresent());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList());
    }

    @Test
    void resolveAndConsumeTicket_blankOrNullTicket_shortCircuits() {
        assertFalse(service.resolveAndConsumeTicket(null).isPresent());
        assertFalse(service.resolveAndConsumeTicket("  ").isPresent());

        verifyNoInteractions(redisTemplate);
    }

    // ==================== 一次性语义：并发下只有一方能拿到 ====================

    @Test
    void resolveAndConsumeTicket_alreadyConsumedByConcurrentHandshake_fails() {
        when(valueOperations.get("ws:ticket:race")).thenReturn("{\"userId\":1,\"saToken\":\"t\"}");
        // 另一方已抢先 GETDEL：本次拿到 null
        when(redisTemplate.execute(any(RedisScript.class), anyList())).thenReturn(null);

        assertFalse(service.resolveAndConsumeTicket("race").isPresent(),
                "并发下未抢到 GETDEL 的一方必须失败，保持一次性语义");
    }

    // ==================== createTicket ====================

    @Test
    void createTicket_writesJsonUnderPrefixedKeyWithConfiguredTtl() {
        String uuid = service.createTicket(42L, "token-xyz");

        verify(valueOperations).set(eq("ws:ticket:" + uuid), anyString(), eq(java.time.Duration.ofSeconds(30)));
        assertTrue(uuid.matches("[0-9a-f\\-]{36}"));
    }
}
