package uno.acloud.project.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app")
public class ServiceProperties {

    private final ServiceUrl userService = new ServiceUrl();
    private final ServiceUrl fileService = new ServiceUrl();
    private final ServiceUrl teamService = new ServiceUrl();
    private final ServiceUrl emailService = new ServiceUrl();
    private String internalServiceToken;
    private final Storage storage = new Storage();

    public ServiceUrl getUserService() { return userService; }
    public ServiceUrl getFileService() { return fileService; }
    public ServiceUrl getTeamService() { return teamService; }
    public ServiceUrl getEmailService() { return emailService; }
    public String getInternalServiceToken() { return internalServiceToken; }
    public void setInternalServiceToken(String internalServiceToken) { this.internalServiceToken = internalServiceToken; }
    public Storage getStorage() { return storage; }

    public static class ServiceUrl {
        private String baseUrl;
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String normalizedBaseUrl() {
            if (!StringUtils.hasText(baseUrl)) {
                throw new IllegalStateException("服务地址未配置");
            }
            return StringUtils.trimTrailingCharacter(baseUrl.trim(), '/');
        }
    }

    public static class Storage {
        private long personalDefaultLimit = 10737418240L;
        public long getPersonalDefaultLimit() { return personalDefaultLimit; }
        public void setPersonalDefaultLimit(long personalDefaultLimit) { this.personalDefaultLimit = personalDefaultLimit; }
    }
}
