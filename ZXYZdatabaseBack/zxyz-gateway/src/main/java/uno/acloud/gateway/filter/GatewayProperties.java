package uno.acloud.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("appGatewayProperties")
@ConfigurationProperties(prefix = "app")
public class GatewayProperties {

    private final Cors cors = new Cors();
    public Cors getCors() { return cors; }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173,http://localhost:4173";
        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
