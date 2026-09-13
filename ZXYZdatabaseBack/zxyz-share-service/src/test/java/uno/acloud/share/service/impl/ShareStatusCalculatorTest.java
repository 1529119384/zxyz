package uno.acloud.share.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 审计 D3：过期判定必须走注入的时间基准（{@link ShareTimeSource}），
 * 不得再自行调用 {@code LocalDateTime.now()}。
 *
 * <p>用固定时钟把「此刻」钉在 {@code 2026-09-13T00:00:00Z}，于是
 * 「刚过期」与「还差 1 分钟过期」两侧都可确定性断言 —— 而这正是改造前测不出来的部分。</p>
 */
@ExtendWith(MockitoExtension.class)
class ShareStatusCalculatorTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 0, 0);

    @Mock
    private ShareMapper shareMapper;

    private ShareStatusCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new ShareStatusCalculator(shareMapper, new ShareTimeSource(FIXED_CLOCK));
    }

    @Test
    @DisplayName("过期时间早于此刻 → 判为已过期")
    void expireTimeBeforeNowIsExpired() {
        Share share = normalShare();
        share.setExpireTime(NOW.minusMinutes(1));

        assertEquals(ShareStatus.EXPIRED, calculator.calculateShareStatus(share));
    }

    @Test
    @DisplayName("过期时间晚于此刻 → 仍为正常")
    void expireTimeAfterNowIsNormal() {
        Share share = normalShare();
        share.setExpireTime(NOW.plusMinutes(1));

        assertEquals(ShareStatus.NORMAL, calculator.calculateShareStatus(share));
    }

    @Test
    @DisplayName("无过期时间 → 永久有效")
    void nullExpireTimeMeansPermanent() {
        Share share = normalShare();
        share.setExpireTime(null);

        assertEquals(ShareStatus.NORMAL, calculator.calculateShareStatus(share));
    }

    @Test
    @DisplayName("已取消优先于过期判定")
    void canceledTakesPrecedenceOverExpired() {
        Share share = normalShare();
        share.setStatus(ShareStatus.CANCELED);
        share.setExpireTime(NOW.minusDays(1));

        assertEquals(ShareStatus.CANCELED, calculator.calculateShareStatus(share));
    }

    @Test
    @DisplayName("访问次数用尽 → 判为达到上限")
    void accessCountReachingLimitIsReported() {
        Share share = normalShare();
        share.setExpireTime(NOW.plusDays(1));
        share.setMaxAccessCount(3);
        share.setCurrentAccessCount(3);

        assertEquals(ShareStatus.ACCESS_LIMIT_REACHED, calculator.calculateShareStatus(share));
    }

    @Test
    @DisplayName("访问次数为空 → 视为零次")
    void nullAccessCountIsTreatedAsZero() {
        Share share = normalShare();
        share.setExpireTime(NOW.plusDays(1));
        share.setMaxAccessCount(1);
        share.setCurrentAccessCount(null);

        assertEquals(ShareStatus.NORMAL, calculator.calculateShareStatus(share));
    }

    private Share normalShare() {
        Share share = new Share();
        share.setId(1L);
        share.setStatus(ShareStatus.NORMAL);
        return share;
    }
}
