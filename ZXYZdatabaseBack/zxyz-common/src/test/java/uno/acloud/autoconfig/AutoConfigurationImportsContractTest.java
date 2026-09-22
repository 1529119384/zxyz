package uno.acloud.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 自动配置注册表契约门禁（C-10 的常驻护栏）。
 *
 * <h2>它守的是什么</h2>
 * 本仓的启动类普遍写 {@code @ComponentScan(basePackages = {"uno.acloud.<svc>", "uno.acloud.common"})}。
 * 一旦某个自动配置类<b>落在 {@code uno.acloud.common} 下</b>，它就会同时被两条路径注册：
 * <ol>
 *   <li>组件扫描（常规解析阶段，较早）；</li>
 *   <li>{@code AutoConfiguration.imports}（{@code DeferredImportSelector}，很晚）。</li>
 * </ol>
 * 同一份配置被注册两次、条件在两个阶段各求值一次，而「谁先用上」取决于 Spring 内部步序。
 * 实测（{@code AuditBufferRetryAutoConfiguration} 的类注释里有完整数据）：被扫到的那一份
 * 会把 {@code @ConditionalOnBean} 求值为 <b>false</b>，Bean <b>静默不创建</b>，
 * 全过程无日志无异常 —— 本仓已因此真实丢过「审计缓冲重试任务」与两条权限缓存订阅。
 *
 * <p>因此把「包即契约」变成断言：<b>注册表里的每一个类，都必须在 {@code uno.acloud.autoconfig}
 * 下且带 {@code @AutoConfiguration}</b>。</p>
 *
 * <h2>为什么不用 ArchUnit</h2>
 * 架构规则集中挂在 {@code zxyz-admin-service} 的 {@code ArchitectureRulesTest}（跨模块形态），
 * 但那里每条规则都要经 {@code FreezingArchRule} 冻结存量，首次落地需要一次
 * {@code -Darchunit.bootstrap=true} 并提交基线文件。本规则的口径是「<b>零容忍</b>」——
 * 注册表必须整体合规，没有可冻结的存量 —— 用冻结规则反而会掩盖新增违规。
 * 且本断言直接读<b>注册表本身</b>（真正的契约），而不是读「谁被注解了」这种代理指标；
 * 它随 {@code zxyz-common} 的测试一起跑，不需要全量 reactor 构建，反馈最快。
 */
class AutoConfigurationImportsContractTest {

    /** Spring Boot 3 的自动配置注册表位置（本模块唯一一份）。 */
    private static final String REGISTRY_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** 被各服务 {@code @ComponentScan} 覆盖的前缀 —— 自动配置类绝不能落在它下面。 */
    private static final String SCANNED_PACKAGE_PREFIX = "uno.acloud.common.";

    /**
     * 注册表条目数下界。存在意义是<b>反空扫</b>：若资源读取方式失效（路径写错、打包变化），
     * 列表会变成空，循环体一次都不跑 ⇒ 门禁变成「恒绿」。宁可响亮失败，也不要空扫。
     */
    private static final int MIN_EXPECTED_ENTRIES = 9;

    @Test
    void registryIsNotEmptyAndEveryEntryIsRelocatedAndAnnotated() {
        List<String> entries = readOwnRegistry();

        assertTrue(entries.size() >= MIN_EXPECTED_ENTRIES,
                "只读到 " + entries.size() + " 条注册项（下界 " + MIN_EXPECTED_ENTRIES
                        + "）⇒ 注册表读取方式可能已失效，先修门禁本身，别让它静默变成空扫");

        assertNoViolations(entries);
    }

    /**
     * 门禁自检：喂一份<b>已知违规</b>的清单，确认它真的会红。
     *
     * <p>没有这一条，上面那个测试「通过」只能说明它没报错 —— 而不能说明它<b>会</b>报错。
     * 本仓反复踩过「门禁看起来在检查、实际从不失败」的坑，故把反例做成常驻用例。</p>
     */
    @Test
    void theGateItselfRejectsKnownViolations() {
        List<String> knownBad = List.of(
                "uno.acloud.common.config.CacheConfig",                       // 扫描包内（C-10 违规形态）
                "uno.acloud.autoconfig.AuditBufferRetryAutoConfiguration");   // 合规项，混入以证明不是全盘报错

        AssertionError error = assertThrows(AssertionError.class,
                () -> assertNoViolations(knownBad),
                "扫描包内的自动配置必须让门禁变红；若这里不抛异常，本门禁形同虚设");
        String message = String.valueOf(error.getMessage());
        assertTrue(message.contains("uno.acloud.common.config.CacheConfig"),
                "报错信息必须点名具体违规项，实际为：" + message);
        assertFalse(message.contains("AuditBufferRetryAutoConfiguration"),
                "合规项不应被误报，实际为：" + message);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static List<String> readOwnRegistry() {
        ClassLoader loader = AutoConfigurationImportsContractTest.class.getClassLoader();
        InputStream stream = loader.getResourceAsStream(REGISTRY_RESOURCE);
        if (stream == null) {
            fail("在 classpath 上找不到 " + REGISTRY_RESOURCE
                    + " ⇒ 本门禁扫不到目标，宁可响亮失败也不要『扫不到就算通过』");
        }
        List<String> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                // `#` 开头是注释（Spring 的 ImportCandidates 同样跳过），空行跳过。
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                entries.add(trimmed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + REGISTRY_RESOURCE + " 失败", e);
        }
        assertFalse(entries.isEmpty(), "解析后条目为空 ⇒ 读取逻辑有问题");
        return entries;
    }

    /** 逐条检查并一次性汇总报错（便于一次看到全部违规，而不是改一条跑一次）。 */
    private static void assertNoViolations(List<String> fullyQualifiedNames) {
        List<String> problems = new ArrayList<>();
        for (String fqn : fullyQualifiedNames) {
            Class<?> type;
            try {
                type = Class.forName(fqn, false,
                        AutoConfigurationImportsContractTest.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                problems.add(fqn + " —— 注册表里的类在 classpath 上不存在（拼写错误？类被删了？）");
                continue;
            }
            if (!type.isAnnotationPresent(AutoConfiguration.class)) {
                problems.add(fqn + " —— 缺少 @AutoConfiguration。Spring Boot 3 要求注册表里的类"
                        + "必须用它而不是 @Configuration（前者才有确定的前后置排序能力）");
            }
            if (fqn.startsWith(SCANNED_PACKAGE_PREFIX)) {
                problems.add(fqn + " —— 位于被 @ComponentScan 覆盖的包下（" + SCANNED_PACKAGE_PREFIX
                        + "**）。这类配置会被扫描提前注册，其中的 @ConditionalOnBean 将恒定求值为 false、"
                        + "Bean 静默不创建（C-10/C-12 已实测）。请迁入 uno.acloud.autoconfig");
            }
        }
        if (!problems.isEmpty()) {
            fail("自动配置注册表存在 " + problems.size() + " 处违规：\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    /** 供将来核对：注册表条目数应与本类常量一致（改动时两边一起改）。 */
    @Test
    void entryCountMatchesTheDocumentedLowerBound() {
        assertEquals(MIN_EXPECTED_ENTRIES, readOwnRegistry().size(),
                "注册表条目数变了就请同步更新 MIN_EXPECTED_ENTRIES —— 它是反空扫的下界");
    }
}
