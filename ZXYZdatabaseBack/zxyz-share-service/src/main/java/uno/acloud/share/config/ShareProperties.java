package uno.acloud.share.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.share")
public class ShareProperties {

    private String cookieSecret;
    private String frontendBaseUrl;

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
}
