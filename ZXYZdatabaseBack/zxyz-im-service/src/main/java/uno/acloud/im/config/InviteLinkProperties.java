package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.invite-link")
public class InviteLinkProperties {
    private int defaultExpireHours = 24;
    /**
     * 邀请链接默认使用次数上限（60 号 🟡-6）：0 = 不限次会放大「持链接者无权限反复触发
     * 加入申请 → 全员审批通知骚扰」面，默认改为有限值；管理员仍可在创建时显式指定 maxUses。
     */
    private int defaultMaxUses = 50;
    public int getDefaultExpireHours() { return defaultExpireHours; }
    public void setDefaultExpireHours(int defaultExpireHours) { this.defaultExpireHours = defaultExpireHours; }
    public int getDefaultMaxUses() { return defaultMaxUses; }
    public void setDefaultMaxUses(int defaultMaxUses) { this.defaultMaxUses = defaultMaxUses; }
}
