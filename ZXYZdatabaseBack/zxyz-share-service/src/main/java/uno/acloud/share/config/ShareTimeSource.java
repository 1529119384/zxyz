package uno.acloud.share.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * share 服务的<b>唯一时间基准</b>（审计 D3）。
 *
 * <p>改造前，share 服务有三处各自调用 {@link LocalDateTime#now()}：</p>
 * <ul>
 *   <li>写入端 {@code ShareManager#createShare}：决定 {@code create_time} / {@code expire_time}；</li>
 *   <li>{@code ShareStatusCalculator}：判定分享是否过期；</li>
 *   <li>{@code ShareCookieManager#resolveCookieMaxAge}：决定 Cookie 的 {@code maxAge}。</li>
 * </ul>
 *
 * <p>这三处都隐式依赖 JVM 默认时区（= 容器 {@code TZ}）。四方一致（JVM / DB 会话 /
 * MySQL 容器 / {@code TZ} 环境变量）时它们自洽，但这条一致性是**靠环境变量维持的隐性不变量**：
 * 一旦有人删掉 compose 里的 {@code TZ}、或在 {@code TZ=UTC} 的机器上直跑服务，
 * 「写入」与「判定」会同时偏 8 小时 ⇒ 症状是「分享链接提前失效 / 超期仍可访问」。</p>
 *
 * <p>本类把三处收敛到一个可注入的 {@link Clock}：默认仍是 JVM 默认时区
 * （⇒ 行为零变化），但基准从此是<b>显式且唯一</b>的，并可用 {@code app.share.time-zone} 钉死，
 * 也可以用固定 {@link Clock} 写确定性单测（否则「跨零点算出两个不同值」这类 bug 无法回归）。</p>
 *
 * <p>注意：本类<b>不</b>参与时区换算，只提供「此刻」。{@code ShareCookieManager} 与
 * {@code ShareStatusCalculator} 都不得再自行调用 {@link LocalDateTime#now()}。</p>
 */
@Slf4j
@Component
public class ShareTimeSource {

    private final Clock clock;

    @Autowired
    public ShareTimeSource(ShareProperties shareProperties) {
        this(resolveClock(shareProperties));
        log.info("[D3] share 时间基准 = {}（来源：{}）",
                clock.getZone(),
                (shareProperties.getTimeZone() == null || shareProperties.getTimeZone().isBlank())
                        ? "JVM 默认时区 / 容器 TZ（app.share.time-zone 未设置）"
                        : "app.share.time-zone 显式指定");
    }

    /** 供测试注入固定时钟（{@link Clock#fixed} 等），使依赖时间的逻辑可确定性回归。 */
    public ShareTimeSource(Clock clock) {
        this.clock = clock;
    }

    /** 当前时刻（基准见类注释）。 */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static Clock resolveClock(ShareProperties shareProperties) {
        String zoneId = shareProperties == null ? null : shareProperties.getTimeZone();
        if (zoneId == null || zoneId.isBlank()) {
            return Clock.systemDefaultZone();
        }
        // 非法时区在启动期直接抛 IllegalArgumentException（fail-fast，胜过运行期悄悄用默认时区）
        return Clock.system(ZoneId.of(zoneId.trim()));
    }
}
