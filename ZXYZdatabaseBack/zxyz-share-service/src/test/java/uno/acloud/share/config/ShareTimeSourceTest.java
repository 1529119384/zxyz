package uno.acloud.share.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计 D3：share 服务的时间基准必须是**显式且可注入**的。
 *
 * <p>这些用例锁住三件事：① 不配置时行为与改造前一致（走 JVM 默认时区）；
 * ② 显式配置能把基准钉死；③ 固定 {@link Clock} 能让依赖时间的逻辑确定性回归。</p>
 */
class ShareTimeSourceTest {

    /** 允许的时钟抖动（秒）：只为容纳「取 now() 与断言 now() 之间的耗时」。 */
    private static final long TOLERANCE_SECONDS = 5;

    @Test
    @DisplayName("未设置时区时走 JVM 默认时区，行为与改造前一致")
    void blankTimeZoneFallsBackToJvmDefaultZone() {
        ShareProperties properties = new ShareProperties();
        properties.setTimeZone("   ");   // 全空白也视为未设置

        LocalDateTime actual = new ShareTimeSource(properties).now();
        long delta = Math.abs(Duration.between(LocalDateTime.now(), actual).getSeconds());

        assertTrue(delta <= TOLERANCE_SECONDS,
                "留空时应等于 JVM 默认时区的当前时刻，实际偏差 " + delta + "s");
    }

    @Test
    @DisplayName("显式时区能把时间基准钉死")
    void explicitTimeZonePinsTheBasis() {
        ShareProperties properties = new ShareProperties();
        properties.setTimeZone("Asia/Shanghai");

        LocalDateTime actual = new ShareTimeSource(properties).now();
        LocalDateTime expected = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        long delta = Math.abs(Duration.between(expected, actual).getSeconds());

        assertTrue(delta <= TOLERANCE_SECONDS,
                "显式时区应等于该时区的墙钟时间，实际偏差 " + delta + "s");
    }

    @Test
    @DisplayName("固定时钟下取到确定的墙钟时间")
    void fixedClockYieldsDeterministicWallClockTime() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneId.of("Asia/Shanghai"));

        assertEquals(LocalDateTime.of(2026, 9, 13, 8, 0), new ShareTimeSource(fixed).now());
    }

    @Test
    @DisplayName("非法时区在构造期快速失败")
    void invalidTimeZoneFailsFast() {
        ShareProperties properties = new ShareProperties();
        properties.setTimeZone("Not/AZone");

        assertThrows(DateTimeException.class, () -> new ShareTimeSource(properties));
    }
}
