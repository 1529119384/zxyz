package uno.acloud.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImWebSocketRouteValidator} 测试（B-10）。
 *
 * <p>要守的取舍：prod 档漏注入 {@code IM_WEBSOCKET_URI} 必须<b>启动失败</b>
 * （否则全站 WS 静默指向 localhost），而非 prod 档必须<b>照常启动</b>
 * （本地开发者不该被迫编造容器主机名）。</p>
 */
class ImWebSocketRouteValidatorTest {

    private ImWebSocketRouteValidator validator(String websocketUri, String activeProfiles) {
        GatewayProperties properties = new GatewayProperties();
        properties.getGateway().setWebsocketUri(websocketUri);
        return new ImWebSocketRouteValidator(properties, activeProfiles);
    }

    @Test
    void prodWithoutExplicitUri_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator("", "prod").verifyWebSocketRouteConfigured());

        assertTrue(ex.getMessage().contains("IM_WEBSOCKET_URI"),
                "失败信息必须点名环境变量，实际：" + ex.getMessage());
    }

    @Test
    void prodWithExplicitUri_passes() {
        assertDoesNotThrow(() -> validator("ws://im-service:19090", "prod")
                .verifyWebSocketRouteConfigured());
    }

    @Test
    void devWithoutExplicitUri_isAllowed() {
        // 本地开发：路由回退到 yml 的 localhost 默认值，这是有意保留的行为
        assertDoesNotThrow(() -> validator("", "dev").verifyWebSocketRouteConfigured());
    }

    @Test
    void noProfileWithoutExplicitUri_isAllowed() {
        // 网关刻意不设 spring.profiles.default，未指定档位时不得因本门禁而起不来
        assertDoesNotThrow(() -> validator("", "").verifyWebSocketRouteConfigured());
    }

    @Test
    void prodAmongMultipleProfiles_stillFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> validator("", "prod,metrics").verifyWebSocketRouteConfigured());
    }
}
