package uno.acloud.email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String internalServiceToken;
    public String getInternalServiceToken() { return internalServiceToken; }
    public void setInternalServiceToken(String internalServiceToken) { this.internalServiceToken = internalServiceToken; }
}
