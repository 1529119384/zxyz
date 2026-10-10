package uno.acloud.im.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ImSessionKeepAliveService} 会话续签的<b>节流与静默</b>测试。
 *
 * <p>要守的不变量：WS 连接存活期间，同一连接至多每 5 分钟触发一次底层
 * Sa-Token 续签（{@code StpLogic.updateLastActiveToNow(tokenValue)}）；
 * token 缺失、未认证连接、底层异常一律静默，绝不影响消息主链路。
 * 底层调用经 {@link SaTokenRenewDelegate} mock 验证，不触真 StpUtil。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImSessionKeepAliveServiceTest {

    @Mock
    private SaTokenRenewDelegate renewDelegate;

    private ImSessionKeepAliveService service;

    @BeforeEach
    void setUp() {
        service = new ImSessionKeepAliveService(renewDelegate);
    }

    @Test
    void 成功路径恰好调用一次底层续签() {
        AtomicLong lastRenewAt = new AtomicLong(0L);

        boolean renewed = service.renew(lastRenewAt, "token-abc");

        assertTrue(renewed, "首次续签（节流时间戳为 0）应实际执行");
        verify(renewDelegate, times(1)).updateLastActiveToNow("token-abc");
    }

    @Test
    void 五分钟内的第二次续签被节流跳过() {
        AtomicLong lastRenewAt = new AtomicLong(0L);
        service.renew(lastRenewAt, "token-abc");

        // 首次续签已把时间戳更新为当前时间；紧接着的第二次调用必须被 5 分钟节流拦截
        boolean second = service.renew(lastRenewAt, "token-abc");

        assertFalse(second, "5 分钟内的第二次 renew 应被节流跳过");
        verify(renewDelegate, times(1)).updateLastActiveToNow("token-abc");
    }

    @Test
    void 超过节流间隔后允许再次续签() {
        // 模拟「上次续签发生在 6 分钟前」
        AtomicLong lastRenewAt = new AtomicLong(
                System.currentTimeMillis() - ImSessionKeepAliveService.MIN_RENEW_INTERVAL_MS - 60_000L);

        boolean renewed = service.renew(lastRenewAt, "token-abc");

        assertTrue(renewed, "距上次续签超过 5 分钟应放行");
        verify(renewDelegate, times(1)).updateLastActiveToNow("token-abc");
    }

    @Test
    void token为空白时静默跳过且不触发底层续签() {
        assertTrue(service.renew(null, null) == false, "token 为 null 应静默返回 false");
        assertTrue(service.renew(null, "") == false, "token 为空串应静默返回 false");
        assertTrue(service.renew(null, "   ") == false, "token 为空白应静默返回 false");
        verify(renewDelegate, never()).updateLastActiveToNow(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 节流时间戳缺失时仍可续签() {
        // Channel attr 未初始化（如旧连接无该 attr）时 lastRenewAt 为 null：视作从未续签，放行
        boolean renewed = service.renew(null, "token-abc");

        assertTrue(renewed, "lastRenewAt 为 null 应视作从未续签");
        verify(renewDelegate, times(1)).updateLastActiveToNow("token-abc");
    }

    @Test
    void 底层续签抛异常时静默不外抛() {
        doThrow(new RuntimeException("redis down")).when(renewDelegate).updateLastActiveToNow("token-abc");
        AtomicLong lastRenewAt = new AtomicLong(0L);

        boolean renewed = service.renew(lastRenewAt, "token-abc");

        assertFalse(renewed, "底层异常应被吞掉并返回 false");
        verify(renewDelegate, times(1)).updateLastActiveToNow("token-abc");
    }
}
