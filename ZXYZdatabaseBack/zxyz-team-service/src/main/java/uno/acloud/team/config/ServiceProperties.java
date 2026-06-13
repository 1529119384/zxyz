package uno.acloud.team.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app")
public class ServiceProperties {

    private final ServiceUrl userService = new ServiceUrl();
    private final ServiceUrl imService = new ServiceUrl();
    private final ServiceUrl emailService = new ServiceUrl();
    private final ServiceUrl fileService = new ServiceUrl();
    private final ServiceUrl projectService = new ServiceUrl();
    private String internalServiceToken;

    public ServiceUrl getUserService() {
        return userService;
    }

    public ServiceUrl getImService() {
        return imService;
    }

    public ServiceUrl getEmailService() {
        return emailService;
    }

    public ServiceUrl getFileService() {
        return fileService;
    }

    public ServiceUrl getProjectService() {
        return projectService;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public static class ServiceUrl {
        private String baseUrl;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String normalizedBaseUrl() {
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalStateException("服务地址未配置");
            }
            return StringUtils.trimTrailingCharacter(baseUrl.trim(), '/');
        }
    }
}
