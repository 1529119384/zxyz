package uno.acloud.admin.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 守护测试：证明 {@code config.datasource.*} 里写的**连接池容量键**真的绑定到了
 * {@link HikariDataSource} 上（审计编号 C-16）。
 *
 * <p>背景缺陷（修复前）：{@code application-dev.yml} / {@code application-prod.yml} 把池参数写在
 * 多余的一层 {@code config.datasource.hikari.maximum-pool-size} 下。但
 * {@code DataSourceBuilder.create().build()} 对 MySQL 返回的是 {@link HikariDataSource}，
 * 它把 {@code maximumPoolSize}/{@code minimumIdle} 暴露为**直接属性**（没有 {@code hikari} 这一层）。
 * 于是 {@code @ConfigurationProperties("config.datasource")} 根本没有
 * {@code config.datasource.hikari.*} 的绑定目标，且 {@code ignoreUnknownFields} 默认 {@code true}
 * ⇒ <b>静默忽略</b>，池跑在 Hikari 默认值上（{@code maximumPoolSize=10}、{@code minimumIdle=10}），
 * 而不是 yml 声明想要的 {@code 5 / 2}。</p>
 *
 * <p>本测试断言的是**可观测行为**（绑定后的池容量 == yml 声明值），而不是 yml 的键形状，
 * 也不是 {@code assertNotNull}：<b>先红后绿</b>——修复前运行必然失败，修复后必然通过。</p>
 *
 * <p>fail-closed：拿不到 yml / 拿不到被守护的键 / 拿不到真实注解前缀，都会**失败**而非静默通过。</p>
 *
 * <p>该测试读的是生产/开发档真实 yml（{@code src/main/resources/application-*.yml}，在测试
 * classpath 上），并复用生产配置类的真实 {@code @ConfigurationProperties} 前缀，故能捕获
 * 「改了注解前缀」与「yml 层写错」两类回归。</p>
 */
class ConfigDataSourcePoolBindingTest {

    /** 生产配置类上真实的绑定前缀 —— 从注解反射取得，避免与生产配置脱节。 */
    private static final String PREFIX = resolveRealPrefix();

    @ParameterizedTest(name = "config.datasource 池容量必须从 {0} 绑定到 HikariDataSource")
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    @DisplayName("C-16: yml 声明的 maximum-pool-size / minimum-idle 必须真正绑到 HikariDataSource")
    void ymlPoolCapacityMustBindToHikariDataSource(String yml) {
        Map<String, String> flat = loadFlattened(yml);

        String maxKey = requireKeyEndingWith(flat, ".maximum-pool-size");
        String minKey = requireKeyEndingWith(flat, ".minimum-idle");
        int declaredMax = parseInt(yml, maxKey, flat.get(maxKey));
        int declaredMin = parseInt(yml, minKey, flat.get(minKey));

        // 与生产配置类同款的构建路径：DataSourceBuilder 对 MySQL 返回 HikariDataSource。
        DataSource built = DataSourceBuilder.create().build();
        assertThat(built)
                .as("DataSourceBuilder 在 MySQL/HikariCP classpath 下应产出 HikariDataSource，否则本守护失效")
                .isInstanceOf(HikariDataSource.class);
        HikariDataSource dataSource = (HikariDataSource) built;

        // 用生产实际使用的绑定前缀，把 yml 里的 config.datasource.* 绑到该实例上 —— 与
        // @ConfigurationProperties 走的是同一套 Binder 机制。
        Binder binder = new Binder(new MapConfigurationPropertySource(flat));
        binder.bind(PREFIX, Bindable.ofInstance(dataSource));

        assertThat(dataSource.getMaximumPoolSize())
                .as("%s 声明的 %s=%d，但绑定后 HikariDataSource.maximumPoolSize 不是该值"
                        + "（典型症状：yml 多套了一层 hikari: 而被静默忽略，池跑在默认 10）",
                        yml, maxKey, declaredMax)
                .isEqualTo(declaredMax);
        assertThat(dataSource.getMinimumIdle())
                .as("%s 声明的 %s=%d，但绑定后 HikariDataSource.minimumIdle 不是该值"
                        + "（典型症状：yml 多套了一层 hikari: 而被静默忽略）",
                        yml, minKey, declaredMin)
                .isEqualTo(declaredMin);
    }

    // ------------------------------------------------------------------
    // 读取真实生产配置类的绑定前缀
    // ------------------------------------------------------------------

    private static String resolveRealPrefix() {
        try {
            Method bean = ConfigDataSourceConfig.class.getDeclaredMethod("configDataSource");
            ConfigurationProperties annotation =
                    AnnotationUtils.findAnnotation(bean, ConfigurationProperties.class);
            if (annotation == null) {
                fail("ConfigDataSourceConfig.configDataSource() 缺少 @ConfigurationProperties —— 守护前提被破坏");
                return null;
            }
            String prefix = StringUtils.hasText(annotation.value()) ? annotation.value() : annotation.prefix();
            if (!StringUtils.hasText(prefix)) {
                fail("@ConfigurationProperties 未声明前缀 —— 守护前提被破坏");
            }
            return prefix;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(
                    "找不到 ConfigDataSourceConfig.configDataSource()，守护测试与生产配置脱节", ex);
        }
    }

    // ------------------------------------------------------------------
    // yml 读取与展平（fail-closed）
    // ------------------------------------------------------------------

    private static Map<String, String> loadFlattened(String yml) {
        Map<String, Object> root = loadYaml(yml);
        Object dsNode = root.get(PREFIX); // 顶层前缀，例如 config
        // 前缀可能是点分层级（config.datasource），逐段下钻
        Object cursor = root;
        for (String segment : PREFIX.split("\\.")) {
            if (!(cursor instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                fail("yml %s 里找不到前缀 %s（段缺失：%s）", yml, PREFIX, segment);
                return Map.of();
            }
            cursor = map.get(segment);
        }
        Map<String, String> flat = new LinkedHashMap<>();
        flatten(cursor, PREFIX, flat);
        assertThat(flat).as("yml %s 的 %s.* 不能为空", yml, PREFIX).isNotEmpty();
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String yml) {
        ClassLoader loader = ConfigDataSourcePoolBindingTest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(yml)) {
            if (in == null) {
                fail("测试 classpath 上找不到 %s —— fail-closed，不允许静默通过", yml);
                return Map.of();
            }
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                fail("yml %s 解析结果不是 Map（内容为空或格式异常）", yml);
                return Map.of();
            }
            return (Map<String, Object>) loaded;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("读取 " + yml + " 失败", ex);
        }
    }

    private static void flatten(Object node, String prefix, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flatten(entry.getValue(), prefix + "." + entry.getKey(), out);
            }
        } else if (node instanceof List<?> list) {
            out.put(prefix, list.toString());
        } else {
            out.put(prefix, node == null ? "" : String.valueOf(node));
        }
    }

    // ------------------------------------------------------------------
    // fail-closed 取值助手
    // ------------------------------------------------------------------

    private static String requireKeyEndingWith(Map<String, String> flat, String suffix) {
        List<String> hits = flat.keySet().stream().filter(k -> k.endsWith(suffix)).toList();
        if (hits.size() != 1) {
            fail("期望恰好 1 个以 %s 结尾的键，实际 %d 个：%s（yml 结构异常则不静默通过）",
                    suffix, hits.size(), hits);
        }
        return hits.get(0);
    }

    private static int parseInt(String yml, String key, String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (RuntimeException ex) {
            fail("yml %s 的 %s 值 %s 不是整数", yml, key, raw);
            return Integer.MIN_VALUE;
        }
    }
}
