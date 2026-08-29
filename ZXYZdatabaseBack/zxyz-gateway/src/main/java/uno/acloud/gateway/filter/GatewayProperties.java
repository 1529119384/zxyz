package uno.acloud.gateway.filter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 网关自定义配置，绑定前缀 {@code app}。
 * <p>CORS 配置见 {@code app.cors.*}。</p>
 * <p>可信代理 IP 见 {@code app.gateway.trusted-proxies}——用于真实客户端 IP 解析
 * （X-Forwarded-For 取最后一跳可信值）。默认关闭（空集合），生产环境必须显式配置部署拓扑
 * 中紧邻网关的反向代理（如 frontend-nginx 的 IP 或网段），否则不会信任任何 forwarded 头，
 * 从源头上杜绝客户端伪造源 IP。</p>
 */
@Component("appGatewayProperties")
@ConfigurationProperties(prefix = "app")
public class GatewayProperties {

    private final Cors cors = new Cors();
    private final Gateway gateway = new Gateway();

    public Cors getCors() { return cors; }

    public Gateway getGateway() { return gateway; }

    /**
     * 网关真实客户端 IP 解析配置。
     */
    public static class Gateway {
        /** 可信代理 IP（支持单个 IP 或 CIDR 网段，逗号/空格分隔）。默认关：解析基于真实远程地址。 */
        private String trustedProxies = "";

        public String getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(String trustedProxies) { this.trustedProxies = trustedProxies; }

        /**
         * 是否已配置可信代理（feature on 判定）。
         */
        public boolean isTrustedProxyConfigured() {
            return StringUtils.hasText(trustedProxies);
        }

        /**
         * 将逗号分隔的可信代理配置解析为规范化 Set（去空格、去空项、去重）。
         */
        public Set<String> trustedProxySet() {
            Set<String> set = new LinkedHashSet<>();
            if (!isTrustedProxyConfigured()) {
                return set;
            }
            Arrays.stream(trustedProxies.split(","))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .forEach(set::add);
            return set;
        }
    }

    public static class Cors {
        private String allowedOrigins = "http://localhost:5173,http://localhost:4173";
        public String getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(String allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }
}
