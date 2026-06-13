package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.invite-link")
public class InviteLinkProperties {
    private int defaultExpireHours = 24;
    private int defaultMaxUses = 0;
    public int getDefaultExpireHours() { return defaultExpireHours; }
    public void setDefaultExpireHours(int defaultExpireHours) { this.defaultExpireHours = defaultExpireHours; }
    public int getDefaultMaxUses() { return defaultMaxUses; }
    public void setDefaultMaxUses(int defaultMaxUses) { this.defaultMaxUses = defaultMaxUses; }
}
