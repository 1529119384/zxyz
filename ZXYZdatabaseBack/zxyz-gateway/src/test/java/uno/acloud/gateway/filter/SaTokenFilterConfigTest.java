package uno.acloud.gateway.filter;

import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import org.junit.jupiter.api.Test;
import uno.acloud.satoken.AuthServicePort;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaTokenFilterConfigTest {

    private SaTokenFilterConfig createConfigWithOrigins(String allowedOrigins) throws Exception {
        GatewayProperties props = new GatewayProperties();
        props.getCors().setAllowedOrigins(allowedOrigins);
        AuthServicePort authServicePort = org.mockito.Mockito.mock(AuthServicePort.class);
        SaTokenFilterConfig config = new SaTokenFilterConfig(props, authServicePort);
        Method init = SaTokenFilterConfig.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(config);
        return config;
    }

    private boolean invokeIsAllowedOrigin(SaTokenFilterConfig config, String origin) throws Exception {
        Method method = SaTokenFilterConfig.class.getDeclaredMethod("isAllowedOrigin", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(config, origin);
    }

    @Test
    void isAllowedOrigin_wildcard_shouldNotMatch() throws Exception {
        SaTokenFilterConfig config = createConfigWithOrigins("*");

        // Wildcard is intentionally not supported because it is incompatible with
        // Access-Control-Allow-Credentials: true. When "*" is in the config,
        // all origins are rejected to prevent misconfiguration.
        assertFalse(invokeIsAllowedOrigin(config, "http://any-origin.com"));
        assertFalse(invokeIsAllowedOrigin(config, "http://localhost:5173"));
        assertFalse(invokeIsAllowedOrigin(config, "*"));
    }

    @Test
    void isAllowedOrigin_returnsTrueForExactMatch() throws Exception {
        SaTokenFilterConfig config = createConfigWithOrigins("http://localhost:5173");

        assertTrue(invokeIsAllowedOrigin(config, "http://localhost:5173"));
    }

    @Test
    void isAllowedOrigin_returnsFalseForUnlistedOrigin() throws Exception {
        SaTokenFilterConfig config = createConfigWithOrigins("http://localhost:5173");

        assertFalse(invokeIsAllowedOrigin(config, "http://evil.com"));
    }

    @Test
    void isAllowedOrigin_handlesMultipleOrigins() throws Exception {
        SaTokenFilterConfig config = createConfigWithOrigins("http://a.com,http://b.com");

        assertTrue(invokeIsAllowedOrigin(config, "http://a.com"));
        assertTrue(invokeIsAllowedOrigin(config, "http://b.com"));
        assertFalse(invokeIsAllowedOrigin(config, "http://c.com"));
    }

    @Test
    void init_parsesCommaSeparatedOrigins() throws Exception {
        SaTokenFilterConfig config = createConfigWithOrigins("http://a.com, http://b.com , http://c.com");

        Field setField = SaTokenFilterConfig.class.getDeclaredField("allowedOriginSet");
        setField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> originSet = (Set<String>) setField.get(config);

        assertEquals(3, originSet.size());
        assertTrue(originSet.contains("http://a.com"));
        assertTrue(originSet.contains("http://b.com"));
        assertTrue(originSet.contains("http://c.com"));
    }

    @Test
    void getSaReactorFilter_returnsNonNull() {
        GatewayProperties props = new GatewayProperties();
        AuthServicePort authServicePort = org.mockito.Mockito.mock(AuthServicePort.class);
        SaTokenFilterConfig config = new SaTokenFilterConfig(props, authServicePort);

        SaReactorFilter filter = config.getSaReactorFilter();

        assertNotNull(filter);
    }
}
