package uno.acloud.email.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email")
public class EmailProperties {
    private String host;
    private int port = 587;
    private String username;
    @JsonIgnore
    private String password;
    private String from;
    private String configSecret;
    private boolean async = true;
    private boolean enabled = false;
    private int batchSize = 50;
    private int retryFixedDelayMs = 60000;
    private int retryInitialDelayMs = 10000;
    private int verifyCodeExpireMinutes = 10;
    private int ipLimitPerMinute = 3;
    private int emailLimitPerMinute = 1;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
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

    public String getFrom() {
        return from == null || from.isBlank() ? username : from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getConfigSecret() {
        return configSecret;
    }

    public void setConfigSecret(String configSecret) {
        this.configSecret = configSecret;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

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

    public int getRetryFixedDelayMs() {
        return retryFixedDelayMs;
    }

    public void setRetryFixedDelayMs(int retryFixedDelayMs) {
        this.retryFixedDelayMs = retryFixedDelayMs;
    }

    public int getRetryInitialDelayMs() {
        return retryInitialDelayMs;
    }

    public void setRetryInitialDelayMs(int retryInitialDelayMs) {
        this.retryInitialDelayMs = retryInitialDelayMs;
    }

    public int getVerifyCodeExpireMinutes() {
        return verifyCodeExpireMinutes;
    }

    public void setVerifyCodeExpireMinutes(int verifyCodeExpireMinutes) {
        this.verifyCodeExpireMinutes = verifyCodeExpireMinutes;
    }

    public int getIpLimitPerMinute() {
        return ipLimitPerMinute;
    }

    public void setIpLimitPerMinute(int ipLimitPerMinute) {
        this.ipLimitPerMinute = ipLimitPerMinute;
    }

    public int getEmailLimitPerMinute() {
        return emailLimitPerMinute;
    }

    public void setEmailLimitPerMinute(int emailLimitPerMinute) {
        this.emailLimitPerMinute = emailLimitPerMinute;
    }
}
