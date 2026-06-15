package uno.acloud.share.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app.team-service")
public class TeamServiceProperties {

    private String baseUrl;
    private String internalServiceToken;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public String normalizedBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("app.team-service.base-url 未配置");
        }
        return StringUtils.trimTrailingCharacter(baseUrl.trim(), '/');
    }
}
