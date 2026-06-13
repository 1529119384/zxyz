package uno.acloud.file.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app")
public class ServiceProperties {

    private String internalServiceToken;
    private final ServiceUrl teamService = new ServiceUrl();
    private final ServiceUrl projectService = new ServiceUrl();
    private final ServiceUrl shareService = new ServiceUrl();
    private final FileObjectDelete fileObjectDelete = new FileObjectDelete();

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public ServiceUrl getTeamService() {
        return teamService;
    }

    public ServiceUrl getProjectService() {
        return projectService;
    }

    public ServiceUrl getShareService() {
        return shareService;
    }

    public FileObjectDelete getFileObjectDelete() {
        return fileObjectDelete;
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

    public static class FileObjectDelete {
        private boolean enabled = true;
        private int batchSize = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }
}
