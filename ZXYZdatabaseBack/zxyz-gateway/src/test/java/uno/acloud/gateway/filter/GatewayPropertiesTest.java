package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayProperties} 绑定 {@code app.*}。可信代理是**默认关闭**的安全开关：
 * 只有显式配置了紧邻网关的反向代理，才允许信任 X-Forwarded-For。
 */
class GatewayPropertiesTest {

    @Test
    void defaults_trustedProxiesEmptySoFeatureIsOff() {
        GatewayProperties props = new GatewayProperties();

        assertThat(props.getGateway().getTrustedProxies()).isEmpty();
        assertThat(props.getGateway().isTrustedProxyConfigured()).isFalse();
        assertThat(props.getGateway().trustedProxySet()).isEmpty();
    }

    @Test
    void defaults_corsAllowedOriginsAreLocalDevOrigins() {
        GatewayProperties props = new GatewayProperties();

        assertThat(props.getCors().getAllowedOrigins())
                .isEqualTo("http://localhost:5173,http://localhost:4173");
    }

    @Test
    void isTrustedProxyConfigured_whenOnlyWhitespace_false() {
        GatewayProperties props = new GatewayProperties();
        props.getGateway().setTrustedProxies("   ");

        assertThat(props.getGateway().isTrustedProxyConfigured()).isFalse();
    }

    @Test
    void trustedProxySet_trimsDropsBlankEntriesAndDedupesKeepingOrder() {
        GatewayProperties props = new GatewayProperties();
        props.getGateway().setTrustedProxies(" 172.18.0.0/16 , 10.0.0.1 ,,172.18.0.0/16 ");

        // 配置里常见的多余空格/空项/重复不能变成"看起来有 4 条实际 2 条"的幻觉
        assertThat(props.getGateway().trustedProxySet())
                .containsExactly("172.18.0.0/16", "10.0.0.1");
    }

    @Test
    void corsAllowedOrigins_isWritable() {
        GatewayProperties props = new GatewayProperties();
        props.getCors().setAllowedOrigins("http://a.com");

        assertThat(props.getCors().getAllowedOrigins()).isEqualTo("http://a.com");
    }
}
