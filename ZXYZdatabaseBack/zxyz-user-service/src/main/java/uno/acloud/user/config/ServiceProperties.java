package uno.acloud.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "app")
public class ServiceProperties {

    private final ServiceUrl teamService = new ServiceUrl();
    private final ServiceUrl imService = new ServiceUrl();
    private final ServiceUrl emailService = new ServiceUrl();
    private String internalServiceToken;
    private final AuthCookie auth = new AuthCookie();
    private final Verification verification = new Verification();
    private final Admin admin = new Admin();

    public ServiceUrl getTeamService() {
        return teamService;
    }

    public ServiceUrl getImService() {
        return imService;
    }

    public ServiceUrl getEmailService() {
        return emailService;
    }

    public Admin getAdmin() {
        return admin;
    }

    public String getInternalServiceToken() {
        return internalServiceToken;
    }

    public void setInternalServiceToken(String internalServiceToken) {
        this.internalServiceToken = internalServiceToken;
    }

    public AuthCookie getAuth() {
        return auth;
    }

    public Verification getVerification() {
        return verification;
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

    public static class AuthCookie {
        private boolean secure = true;
        private String domain = "";
        private int tokenTimeoutSeconds = 43200;
        private int longLivedTimeoutSeconds = 604800;

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public int getTokenTimeoutSeconds() {
            return tokenTimeoutSeconds;
        }

        public void setTokenTimeoutSeconds(int tokenTimeoutSeconds) {
            this.tokenTimeoutSeconds = tokenTimeoutSeconds;
        }

        public int getLongLivedTimeoutSeconds() {
            return longLivedTimeoutSeconds;
        }

        public void setLongLivedTimeoutSeconds(int longLivedTimeoutSeconds) {
            this.longLivedTimeoutSeconds = longLivedTimeoutSeconds;
        }
    }

    public static class Verification {
        private boolean returnCodeInResponse = true;

        public boolean isReturnCodeInResponse() {
            return returnCodeInResponse;
        }

        public void setReturnCodeInResponse(boolean returnCodeInResponse) {
            this.returnCodeInResponse = returnCodeInResponse;
        }
    }

    /**
     * 部署时初始管理员引导配置（app.admin.bootstrap.*）。
     */
    public static class Admin {
        private final Bootstrap bootstrap = new Bootstrap();

        public Bootstrap getBootstrap() {
            return bootstrap;
        }
    }

    public static class Bootstrap {
        private boolean enabled = true;
        private String username = "admin";
        private String password = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
