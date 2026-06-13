package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "im.message")
public class MessageModerationProperties {
    private int recallWindowSeconds = 120;
    public int getRecallWindowSeconds() { return recallWindowSeconds; }
    public void setRecallWindowSeconds(int recallWindowSeconds) { this.recallWindowSeconds = recallWindowSeconds; }
}
