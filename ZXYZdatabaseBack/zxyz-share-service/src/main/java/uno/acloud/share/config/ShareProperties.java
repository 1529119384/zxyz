package uno.acloud.share.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.share")
public class ShareProperties {

    private String cookieSecret;
    private String frontendBaseUrl;

    /**
     * share 服务的时间基准时区（审计 D3）。
     *
     * <p>留空（默认）= 用 JVM 默认时区（即容器 {@code TZ} 环境变量），
     * <b>与改造前行为完全一致</b>；显式填写（如 {@code Asia/Shanghai}）则把
     * 「签发时刻 / 过期判定 / Cookie maxAge」三处的时间基准从「隐式依赖 TZ 环境变量」
     * 变成「代码里写死的显式值」——这正是 D3 要消除的那条隐性不变量。</p>
     *
     * <p>⚠️ 只用于<b>钉住</b>基准，不用于换算：分享到期与否全部由 share 服务在 Java 侧
     * 比较 {@code LocalDateTime}（share 相关 SQL 没有任何 {@code NOW()} /
     * {@code CURRENT_TIMESTAMP} 参与过期判定，只有 {@code update_time} 的默认值）。
     * 因此必须填成与 JVM {@code TZ} / 写入端一致的时区；填成别的时区会让
     * 「新签发的分享」与「改造前写入的 share 行」出现时差。</p>
     */
    private String timeZone;

    public String getCookieSecret() {
        return cookieSecret;
    }

    public void setCookieSecret(String cookieSecret) {
        this.cookieSecret = cookieSecret;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }
}
