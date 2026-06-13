package uno.acloud.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "im.file-service")
public class FileServiceProperties {

    private String baseUrl;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String normalizedBaseUrl() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("im.file-service.base-url 未配置");
        }
        return StringUtils.trimTrailingCharacter(baseUrl.trim(), '/');
    }
}
