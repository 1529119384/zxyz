package uno.acloud.gateway.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IM WebSocket 路由的配置契约门禁（B-10）。
 *
 * <p>这条路由把 {@code /ws} 直通到 im-service 的 Netty 端口，是<b>唯一</b>绕过
 * {@code SaReactorFilter}（它只 include {@code /api/**}）的入口，因此它的谓词与重写规则
 * 是鉴权面的一部分，值得被测试钉住而不是靠人读 yml：</p>
 *
 * <ul>
 *   <li><b>uri 必须取自环境变量</b>（{@code IM_WEBSOCKET_URI}）：容器内要指向
 *       {@code ws://im-service:19090}，本地指向 {@code localhost}。

 *     若有人把它写成硬编码字面量，compose 注入的值会静默失效、WS 全部连到错误地址。</li>
 *   <li><b>{@code SetPath=/ws}</b>：Netty 只注册了精确路径 {@code /ws}
 *       （{@code ImNettyProperties.websocketPath}）。带子路径的请求
 *       （{@code /ws/xxx}，nginx 的 {@code location /ws} 是前缀匹配，会透传）必须先被
 *       重写成 {@code /ws} 才能被 Netty 接受 —— 缺了这条过滤器，
 *       {@code /ws/**} 会在 Netty 侧握手 404。</li>
 * </ul>
 */
class ImWebSocketRouteContractTest {

    private static final String CONFIG = "application.yml";

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(CONFIG)) {
            assertNotNull(stream, "classpath 上找不到 " + CONFIG);
            return new Yaml().load(stream);
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + CONFIG + " 失败", e);
        }
    }

    /** 定位 {@code spring.cloud.gateway.server.webflux.routes}。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routes() {
        Map<String, Object> root = loadConfig();
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
        Map<String, Object> server = (Map<String, Object>) gateway.get("server");
        Map<String, Object> webflux = (Map<String, Object>) server.get("webflux");
        return (List<Map<String, Object>>) webflux.get("routes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> route(String id) {
        return routes().stream()
                .filter(route -> id.equals(route.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("路由 " + id + " 不存在"));
    }

    @Test
    void imWebSocketRoute_uriComesFromEnvironmentVariable() {
        Object uri = route("im-websocket").get("uri");

        String text = String.valueOf(uri);
        assertTrue(text.contains("IM_WEBSOCKET_URI"),
                "WS 路由的 uri 必须由 IM_WEBSOCKET_URI 注入（容器内指向 im-service、本地指向 localhost），"
                        + "否则 compose 注入的值会静默失效。当前：" + text);
    }

    @Test
    void imWebSocketRoute_matchesWsPathAndSubPaths() {
        Object predicates = route("im-websocket").get("predicates");

        String text = String.valueOf(predicates);
        assertTrue(text.contains("/ws"), "谓词必须至少匹配 /ws，当前：" + text);
        assertTrue(text.contains("/ws/**"),
                "nginx 的 location /ws 是前缀匹配，会把 /ws/xxx 透传进来；"
                        + "谓词需覆盖子路径（再由 SetPath 归一），当前：" + text);
    }

    @Test
    void imWebSocketRoute_rewritesSubPathsToExactNettyPath() {
        Object filters = route("im-websocket").get("filters");

        String text = String.valueOf(filters);
        assertTrue(text.contains("SetPath=/ws"),
                "Netty 只注册精确路径 /ws（ImNettyProperties.websocketPath），"
                        + "子路径请求必须经 SetPath=/ws 归一，否则握手 404。当前：" + text);
    }

    @Test
    void routeIds_areUnique() {
        List<Object> ids = routes().stream().map(route -> route.get("id")).toList();

        assertEquals(ids.size(), ids.stream().distinct().count(),
                "路由 id 必须唯一（Spring Cloud Gateway 用 id 做路由身份），实际：" + ids);
    }
}
