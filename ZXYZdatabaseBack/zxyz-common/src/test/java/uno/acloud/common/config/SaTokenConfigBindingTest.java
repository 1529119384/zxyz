package uno.acloud.common.config;

import cn.dev33.satoken.config.SaTokenConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.yaml.snakeyaml.Yaml;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sa-Token 配置绑定防回归测试（2026-10-10 ISSUE/52）。
 *
 * <p>不拉起 Spring 上下文，纯 {@link Binder}：用 {@link YamlPropertySourceLoader}
 * 读 classpath 的 {@code application-common.yml}，汇聚进 {@link MockEnvironment} 后
 * 绑定到 1.46.0 的 {@code cn.dev33.satoken.config.SaTokenConfig}。</p>
 *
 * <p>守住的回归点：
 * <ul>
 *   <li>现存键位必须仍能绑定出正确值（键名手误/缩进漂移会在这里响亮失败）；</li>
 *   <li>三个死键（{@code is-write-cookie}、{@code token-storage-mode}、
 *       {@code sa-token.redis.prefix}）不得回潮 —— 1.46.0 SaTokenConfig 无对应字段
 *       （javap 实证），Boot 宽松绑定会静默忽略，留着只会误导排障。</li>
 * </ul></p>
 */
class SaTokenConfigBindingTest {

    private static final String DEAD_KEY_WRITE_COOKIE = "sa-token.is-write-cookie";
    private static final String DEAD_KEY_STORAGE_MODE = "sa-token.token-storage-mode";
    private static final String DEAD_KEY_REDIS_PREFIX = "sa-token.redis.prefix";

    // ==================== 加载与绑定 ====================

    private static List<PropertySourceEntry> loadCommonYaml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<org.springframework.core.env.PropertySource<?>> sources = loader.load(
                "application-common", new ClassPathResource("application-common.yml"));
        // 本模块公共 yml 唯一，若未来拆分须同步扩展本测试的加载面
        assertEquals(1, sources.size(), "application-common.yml 应加载出恰好一个 PropertySource");
        return List.of(new PropertySourceEntry(sources.get(0)));
    }

    private static SaTokenConfig bindSaToken(List<PropertySourceEntry> sources) {
        MockEnvironment environment = new MockEnvironment();
        sources.forEach(entry -> environment.getPropertySources().addLast(entry.source()));
        return Binder.get(environment)
                .bind("sa-token", Bindable.of(SaTokenConfig.class))
                .orElseThrow(() -> new AssertionError("sa-token 段绑定 SaTokenConfig 失败：无任何可绑定键"));
    }

    @Test
    @DisplayName("application-common.yml 的 sa-token 现存键位应绑定出正确值")
    void saToken段应绑定出1_46_0真实字段值() throws Exception {
        List<PropertySourceEntry> sources = loadCommonYaml();
        SaTokenConfig config = bindSaToken(sources);

        // 以下断言逐项对应 yml 中实际存在的键，不臆造
        assertEquals("satoken", config.getTokenName(), "token-name 应绑定为 satoken");
        assertEquals(43200L, config.getTimeout(), "timeout 应绑定为 43200");
        assertEquals(1800L, config.getActiveTimeout(), "active-timeout 应绑定为 1800");
        assertEquals(Boolean.TRUE, config.getIsReadCookie(), "is-read-cookie 应绑定为 true");
        assertEquals(Boolean.TRUE, config.getIsConcurrent(), "is-concurrent 应绑定为 true");
        assertEquals(Boolean.FALSE, config.getIsShare(), "is-share 应绑定为 false");
        assertEquals(Boolean.TRUE, config.getDynamicActiveTimeout(), "dynamic-active-timeout 应绑定为 true");
        assertEquals("uuid", config.getTokenStyle(), "token-style 应绑定为 uuid");
        assertEquals(Boolean.TRUE, config.getIsLog(), "is-log 应绑定为 true");
        assertNotNull(config.getCookie(), "SaCookieConfig 嵌套对象应由 Binder 默认创建");
    }

    // ==================== 死键防回归：application-common.yml ====================

    @Test
    @DisplayName("application-common.yml 不得再出现三个死键（is-write-cookie / token-storage-mode / redis.prefix）")
    void applicationCommonYml不得再含三个死键() throws Exception {
        List<PropertySourceEntry> sources = loadCommonYaml();
        Map<?, ?> rawMap = sources.get(0).rawMap();

        // YamlPropertySourceLoader 展平后的 key 是点分路径；注释不会进入 source map
        for (String deadKey : List.of(DEAD_KEY_WRITE_COOKIE, DEAD_KEY_STORAGE_MODE, DEAD_KEY_REDIS_PREFIX)) {
            assertFalse(rawMap.containsKey(deadKey),
                    () -> deadKey + " 已于 2026-10-10 ISSUE/52 删除（1.46.0 无对应字段），不得回潮。"
                            + "现有键样例: " + rawMap.keySet().stream().limit(10).toList());
        }
        // 双通道复核：经 MockEnvironment 再查一遍，防 PropertySource 包装形态变化
        MockEnvironment environment = new MockEnvironment();
        sources.forEach(entry -> environment.getPropertySources().addLast(entry.source()));
        for (String deadKey : List.of(DEAD_KEY_WRITE_COOKIE, DEAD_KEY_STORAGE_MODE, DEAD_KEY_REDIS_PREFIX)) {
            assertFalse(environment.containsProperty(deadKey), deadKey + " 不得重新出现");
        }
        // 反空扫：确认加载通道本身有效（否则上面的「不存在」是空扫假绿）
        assertTrue(rawMap.containsKey("sa-token.token-name"),
                "加载结果里应能读到 sa-token.token-name，否则 YAML 加载本身失效");
    }

    // ==================== 死键防回归：nacos-config/zxyz-static.yml ====================

    @Test
    @DisplayName("nacos-config/zxyz-static.yml 不得再出现三个死键")
    void nacosStaticYml不得再含三个死键() throws Exception {
        // nacos-config 在 monorepo 仓库根（= 后端仓库目录的父目录），与
        // AloneRedisWiringContractTest 的 backendRoot().getParent() 定位口径一致
        Path backendRoot = moduleDir().getParent();
        Path nacosFile = backendRoot == null ? null
                : backendRoot.getParent().resolve("nacos-config").resolve("zxyz-static.yml");
        assertTrue(nacosFile != null && Files.isRegularFile(nacosFile),
                "找不到 nacos 配置文件: " + nacosFile + "（假设 monorepo 根下有 nacos-config/zxyz-static.yml）");

        Map<String, Object> root = new Yaml().load(Files.readString(nacosFile));
        assertNotNull(root, "zxyz-static.yml 应能被解析为 Map");
        Map<String, String> flat = new LinkedHashMap<>();
        flatten("", root, flat);

        for (String deadKey : List.of(DEAD_KEY_WRITE_COOKIE, DEAD_KEY_STORAGE_MODE, DEAD_KEY_REDIS_PREFIX)) {
            assertFalse(flat.containsKey(deadKey),
                    () -> deadKey + " 已于 2026-10-10 ISSUE/52 删除（1.46.0 无对应字段），nacos 侧不得回潮，"
                            + "否则导入 Nacos 会造成与 application-common.yml 的行为漂移。");
        }
        // 反空扫
        assertTrue(flat.containsKey("sa-token.token-name"),
                "解析结果里应能读到 sa-token.token-name，否则 YAML 解析本身失效");
    }

    // ==================== 工具 ====================

    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                flatten(key, entry.getValue(), out);
            }
        } else {
            out.put(prefix, String.valueOf(node));
        }
    }

    private static Path moduleDir() {
        try {
            Path testClasses = Paths.get(SaTokenConfigBindingTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path dir = testClasses.getParent().getParent();
            if (dir == null || !Files.isRegularFile(dir.resolve("pom.xml"))) {
                throw new IllegalStateException("反推出的模块目录里没有 pom.xml: " + dir);
            }
            return dir;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("无法解析测试类的 class 输出位置", e);
        }
    }

    /** PropertySource 及其原始 Map 形态的持有者（source map 键位检查用）。 */
    private record PropertySourceEntry(org.springframework.core.env.PropertySource<?> source) {

        Map<?, ?> rawMap() {
            Object src = source.getSource();
            if (!(src instanceof Map<?, ?> map)) {
                throw new AssertionError("YamlPropertySourceLoader 返回的 source 应为 Map，实际: "
                        + (src == null ? "null" : src.getClass().getName()));
            }
            return map;
        }
    }
}
