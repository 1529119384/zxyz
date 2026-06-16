package uno.acloud.admin.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class AdminServiceProperties {

    private final ServiceUrl emailService = new ServiceUrl();
    private final ServiceUrl fileService = new ServiceUrl();
    private String internalServiceToken;

    public ServiceUrl getEmailService() {
        return emailService;
    }

    public ServiceUrl getFileService() {
        return fileService;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    @Getter
    @Setter
    public static class ServiceUrl {
        private String baseUrl;

        public String normalizedBaseUrl() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("服务地址未配置");
            }
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }
}
