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
        /** 手机验证码最大校验尝试次数，超过即作废（防 6 位码爆破）。 */
        private int phoneCodeMaxAttempts = 5;
        /** 手机验证码发送冷却时长（秒），防止重发接口被当作免费重试。 */
        private int phoneCodeCooldownSeconds = 60;
        /**
         * 验证码摘要 pepper（HMAC-SHA256 的密钥，<b>绝不落库</b>）。
         *
         * <p>来自 {@code app.verification.code-pepper}，缺省回退 Jasypt 主密钥 —— 两者同属
         * 「必须保密的根秘密」这一等级，复用可免去另起一份需要独立托管/轮换的秘密。</p>
         */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String codePepper;
        /**
         * 是否允许使用「仓库内公开写着」的弱 pepper。<b>只有 dev / test profile 才应置 true。</b>
         *
         * <p>默认 false —— 纵深防御：{@code spring.profiles.default} 已由 {@code dev} 翻为 {@code prod}
         * （2026-09-13），漏配 profile 会直接启动失败；但只要有人<b>显式</b>激活 dev / test 而忘了配真实
         * pepper，默认放行仍会造出「服务正常运行、摘要却可被离线穷举」的静默弱配置。
         * 放行必须显式写进 profile 配置文件，评审时一眼可见。</p>
         */
        private boolean allowInsecurePepper;

        public boolean isReturnCodeInResponse() {
            return returnCodeInResponse;
        }

        public void setReturnCodeInResponse(boolean returnCodeInResponse) {
            this.returnCodeInResponse = returnCodeInResponse;
        }

        public int getPhoneCodeMaxAttempts() {
            return phoneCodeMaxAttempts;
        }

        public void setPhoneCodeMaxAttempts(int phoneCodeMaxAttempts) {
            this.phoneCodeMaxAttempts = phoneCodeMaxAttempts;
        }

        public int getPhoneCodeCooldownSeconds() {
            return phoneCodeCooldownSeconds;
        }

        public void setPhoneCodeCooldownSeconds(int phoneCodeCooldownSeconds) {
            this.phoneCodeCooldownSeconds = phoneCodeCooldownSeconds;
        }

        public String getCodePepper() {
            return codePepper;
        }

        public void setCodePepper(String codePepper) {
            this.codePepper = codePepper;
        }

        public boolean isAllowInsecurePepper() {
            return allowInsecurePepper;
        }

        public void setAllowInsecurePepper(boolean allowInsecurePepper) {
            this.allowInsecurePepper = allowInsecurePepper;
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
