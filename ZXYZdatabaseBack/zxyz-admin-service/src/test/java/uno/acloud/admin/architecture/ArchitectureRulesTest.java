package uno.acloud.admin.architecture;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.PackageMatcher;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZXYZ 后端【跨模块】架构约束门禁 —— D5 最先落地的形态（路线 B：只钉现状 + 冻结存量违规）。
 *
 * <h2>为什么这个测试挂在 zxyz-admin-service（而不是 zxyz-common）</h2>
 * 架构规则天然是跨模块的（「Controller 不得直接依赖 Mapper」要同时看 9 个服务），但 ArchUnit 的
 * {@code @AnalyzeClasses} 只能分析【测试模块自己的 classpath】，而本仓每个服务都是独立 Maven 模块、
 * 且没有任何模块依赖其他服务（每个服务只依赖 zxyz-common / zxyz-starter）——
 * 也就是说<b>不存在任何一个模块能在 classpath 上看到全部服务</b>。
 * 把规则放进 zxyz-common 只能扫到 common 自己，是「假绿灯」，比没有更糟。
 * 因此这里改用 {@link ClassFileImporter} 直接读取 reactor 各模块的 {@code target/classes}，
 * 并把测试放在 reactor 的<b>最后一个模块</b> {@code zxyz-admin-service}（没有任何模块依赖它，
 * 因此在全量构建中它最后执行，此时其余模块的 target/classes 都已就绪）。
 * 将来若改走路线 A（新建 zxyz-arch-test 模块），本文件整体搬过去即可。
 *
 * <h2>⚠️ 使用约束：必须全量 reactor 构建（fail-closed）</h2>
 * 因为要读各模块的 {@code target/classes}，本测试<b>不能</b>用 {@code mvn -pl <单模块> [-am] test} 触发 ——
 * 那种调用只编译被选中的模块。模块目录缺失时本测试会<b>直接失败并打印缺失清单</b>，
 * 绝不「扫不到就当通过」（本仓反复踩过「假绿灯」的坑）。正确用法：
 * <pre>
 *   cd ZXYZdatabaseBack &amp;&amp; mvn -B test                                  # CI 跑的就是这一条
 *   # 或先全量编译，再只重跑本模块：
 *   mvn -B -DskipTests install &amp;&amp; mvn -B -pl zxyz-admin-service test
 * </pre>
 *
 * <h2>冻结基线（FreezingArchRule）与「只减不增」纪律</h2>
 * 现状本来就不满足其中几条规则（实测：3 处 Controller 里有 try/catch、Controller 直接依赖 Mapper、
 * FileMapper 因 {@code @TypeDiscriminator} 多态映射有意不继承 {@code BaseMapper}），
 * 所以每条规则都经 {@link FreezingArchRule} 冻结「当前存量违规」，而不是要求一步清零：
 * <ul>
 *   <li>首次运行<b>总是通过</b>，并把违规清单写入基线；</li>
 *   <li>之后只有在<b>新增</b>违规时才失败；已修复的违规会被 ArchUnit 自动从基线删除
 *       ⇒ 基线天然「只减不增」，不需要额外脚本维护；</li>
 *   <li>比对时忽略行号，源码上下移动不会误报。</li>
 * </ul>
 * 基线目录：{@code zxyz-admin-service/src/test/resources/archunit_store/}（<b>必须提交进版本库</b>；
 * 路径在代码里按绝对路径指定，不依赖进程工作目录 —— surefire 与 IDE 的工作目录并不一致，
 * 用相对路径会把基线写到别处，于是规则变成空扫）。具体规则：
 * <ul>
 *   <li><b>常规运行（CI 与本地默认）</b>：ArchUnit 的 {@code freeze.store.default.allowStoreCreation}
 *       保持 <b>false</b> ⇒ <b>基线索引文件一旦缺失，测试直接失败</b>，而不会「悄悄按当前状态重新冻结」。
 *       这是刻意的 fail-closed：<b>删掉基线 = 放宽门禁，必须响亮失败。</b></li>
 *   <li><b>首次落地 / 确需重新冻结</b>：加 {@code -Darchunit.bootstrap=true} 重跑（重建索引并写入当前违规），
 *       然后<b>在提交信息里说明为什么存量变大</b>。这是这套门禁唯一容易被滥用的地方，请自觉。</li>
 *   <li>「只减不增」由 ArchUnit 自动保证（已消失的违规会被从基线里删除、更新，不需要额外脚本）。</li>
 * </ul>
 */
class ArchitectureRulesTest {

    /** 基线目录（相对本模块根）。用绝对路径显式指定，不依赖运行进程的当前工作目录。 */
    private static final String STORE_DIR_RELATIVE_TO_MODULE = "src/test/resources/archunit_store";

    private static final Pattern MODULE_TAG = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    private static Path moduleDir;
    private static List<String> reactorModules;
    private static JavaClasses allServiceClasses;

    @BeforeAll
    static void importEveryReactorModule() {
        moduleDir = resolveModuleDir();
        Path backendRoot = moduleDir.getParent();
        reactorModules = readModuleNames(backendRoot.resolve("pom.xml"));

        Path storeDir = moduleDir.resolve(STORE_DIR_RELATIVE_TO_MODULE).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建冻结基线目录: " + storeDir, e);
        }
        ArchConfiguration.get().setProperty("freeze.store.default.path", storeDir.toString());
        // 默认【不允许】自动创建基线 ⇒ 基线索引缺失时直接失败（详见类头「基线文件的提交」）。
        // 仅首次落地 / 确需重新冻结时才开：mvn -B test -Darchunit.bootstrap=true
        ArchConfiguration.get().setProperty("freeze.store.default.allowStoreCreation",
                Boolean.toString(Boolean.getBoolean("archunit.bootstrap")));

        List<Path> present = new ArrayList<>();
        List<Path> missing = new ArrayList<>();
        for (String module : reactorModules) {
            Path classesDir = backendRoot.resolve(module).resolve("target").resolve("classes");
            if (Files.isDirectory(classesDir)) {
                present.add(classesDir);
            } else {
                missing.add(classesDir);
            }
        }
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("架构测试要求【全量 reactor 构建】，但有 ").append(missing.size())
                    .append(" 个模块的 target/classes 不存在：\n");
            for (Path path : missing) {
                sb.append("  - ").append(path).append('\n');
            }
            sb.append("""

                    原因：架构规则是跨模块的（任何单个模块的 classpath 都看不到全部服务），
                    所以本测试直接读取各模块的 target/classes —— 它不能被 `mvn -pl <单模块> [-am] test` 触发。
                    正确做法（二选一）：
                      • 在 ZXYZdatabaseBack 根目录执行全量测试：  mvn -B test
                      • 先全量编译再只跑本模块：                  mvn -B -DskipTests install && mvn -B -pl zxyz-admin-service test
                    """);
            throw new IllegalStateException(sb.toString());
        }

        allServiceClasses = new ClassFileImporter().importPaths(present);
        assertThat(allServiceClasses.size())
                .as("导入到的类数量明显偏少，说明 target/classes 的解析路径有问题（宁可响亮失败，也不要空扫）")
                .isGreaterThan(200);
    }

    // ==================================================================================
    // 门禁 0：自检 —— 保证上面「发现模块 / 匹配包」的机制本身没有静默失效
    // ==================================================================================

    @Test
    void every_reactor_module_contributes_classes_to_the_import() {
        Set<String> scannedTopPackages = new TreeSet<>();
        for (JavaClass javaClass : allServiceClasses) {
            String packageName = javaClass.getPackageName();
            if (!packageName.startsWith("uno.acloud.")) {
                continue;
            }
            String rest = packageName.substring("uno.acloud.".length());
            int dot = rest.indexOf('.');
            scannedTopPackages.add(dot < 0 ? rest : rest.substring(0, dot));
        }
        for (String module : reactorModules) {
            assertThat(scannedTopPackages)
                    .as("reactor 模块 %s 的类必须真的被扫到，否则针对它的规则形同虚设"
                            + "（新增模块时请确认根 pom 的 <modules> 与包名约定仍然一致）", module)
                    .contains(expectedTopLevelPackage(module));
        }
    }

    @Test
    void package_matcher_semantics_relied_on_by_these_rules_hold() {
        assertThat(PackageMatcher.of("..controller..").matches("uno.acloud.file.controller.admin")).isTrue();
        assertThat(PackageMatcher.of("..controller..").matches("uno.acloud.share.controller.support")).isTrue();
        assertThat(PackageMatcher.of("..controller..").matches("uno.acloud.file.controllerless")).isFalse();

        assertThat(PackageMatcher.of("..service.impl..").matches("uno.acloud.user.service.impl")).isTrue();
        assertThat(PackageMatcher.of("..service.impl..").matches("uno.acloud.user.service")).isFalse();

        assertThat(PackageMatcher.of("uno.acloud.user..").matches("uno.acloud.user")).isTrue();
        assertThat(PackageMatcher.of("uno.acloud.user..").matches("uno.acloud.user.service.impl")).isTrue();
        assertThat(PackageMatcher.of("uno.acloud.user..").matches("uno.acloud.users")).isFalse();
    }

    // ==================================================================================
    // 门禁 1~6：架构规则（每条都冻结存量违规）
    // ==================================================================================

    @Test
    void controller_must_not_depend_on_mapper_directly() {
        checkFrozen(noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..mapper..")
                .because("Controller 是 HTTP 边界；直接依赖 Mapper 会绕过 Service 层的事务、权限、缓存与校验"));
    }

    @Test
    void controller_must_not_swallow_exceptions_with_try_catch() {
        checkFrozen(classes().that().resideInAPackage("..controller..")
                .should(MUST_NOT_DECLARE_TRY_CATCH));
    }

    @Test
    void module_must_not_reach_into_another_modules_service_impl() {
        for (String module : reactorModules) {
            String service = expectedTopLevelPackage(module);
            String implPackage = "uno.acloud." + service + ".service.impl";
            checkFrozen(noClasses().that().resideOutsideOfPackage("uno.acloud." + service + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(implPackage, implPackage + "..")
                    .because(module + " 的 service 实现属于模块内部，其他模块只能经对外契约（REST client / 领域事件）访问"));
        }
    }

    @Test
    void entity_must_not_depend_on_controller() {
        checkFrozen(noClasses().that().resideInAnyPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("依赖方向必须单向：Web 层依赖领域层，反之会造成循环与无法独立测试"));
    }

    @Test
    void mapper_package_interfaces_must_be_named_ending_with_mapper() {
        checkFrozen(classes().that().resideInAPackage("..mapper..").and().areInterfaces()
                .should().haveSimpleNameEndingWith("Mapper")
                .because("MyBatis 的扫描与人工检索都依赖命名的可预测性"));
    }

    @Test
    void mapper_package_interfaces_must_extend_base_mapper() {
        checkFrozen(classes().that().resideInAPackage("..mapper..").and().areInterfaces()
                // ⚠️ `..mapper..` 包里实际住着两类东西：MyBatis 持久层 Mapper，以及 MapStruct 转换器
                // （本仓把后者命名为 *EntityMapper，与前者在同一包 ⇒ 命名撞车）。
                // MapStruct 接口必须带 org.mapstruct.Mapper 注解、本来就不该继承 BaseMapper，
                // 所以按注解精确排除；否则这条规则会把 6 个转换器一起算成「违规」冻结进基线，
                // 规则就失去信号了（按注解而非按类名排除，也为将来改名留了余地）。
                .and().areNotAnnotatedWith("org.mapstruct.Mapper")
                .should().beAssignableTo(BaseMapper.class)
                .because("统一继承 BaseMapper 才能复用通用 CRUD，并让「是不是持久层 Mapper」有唯一判据"));
    }

    @Test
    void production_code_must_not_write_to_stdout_or_stderr() {
        checkFrozen(classes().that().areNotInterfaces().should(MUST_NOT_ACCESS_SYSTEM_STREAMS));
    }

    // ==================================================================================
    // 规则定义与工具
    // ==================================================================================

    /** 所有规则都必须经由它 —— 避免有人漏掉 FreezingArchRule 而让规则一提交就把 CI 打红。 */
    private static void checkFrozen(ArchRule rule) {
        FreezingArchRule.freeze(rule).check(allServiceClasses);
    }

    private static final ArchCondition<JavaClass> MUST_NOT_DECLARE_TRY_CATCH =
            new ArchCondition<JavaClass>("不使用 try/catch（异常统一交给 GlobalExceptionHandler）") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    for (JavaCodeUnit codeUnit : item.getCodeUnits()) {
                        for (TryCatchBlock block : codeUnit.getTryCatchBlocks()) {
                            events.add(SimpleConditionEvent.violated(item, String.format(
                                    "%s 在 %s 使用了 try/catch —— 应让异常冒泡给 GlobalExceptionHandler 统一处理",
                                    block.getOwner().getFullName(), block.getSourceCodeLocation())));
                        }
                    }
                }
            };

    private static final ArchCondition<JavaClass> MUST_NOT_ACCESS_SYSTEM_STREAMS =
            new ArchCondition<JavaClass>("不向 System.out / System.err 写输出（统一用 @Slf4j 日志）") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    item.getFieldAccessesFromSelf().stream()
                            .filter(access -> "java.lang.System.out".equals(access.getTarget().getFullName())
                                    || "java.lang.System.err".equals(access.getTarget().getFullName()))
                            .forEach(access -> events.add(SimpleConditionEvent.violated(item, String.format(
                                    "%s 访问了 %s（%s）", item.getName(),
                                    access.getTarget().getFullName(), access.getDescription()))));
                }
            };

    /**
     * 本模块的根目录。用「测试类自身的 class 输出位置」反推，而不是拿进程的当前工作目录 ——
     * surefire 与 IDE 的工作目录并不一致，用相对路径会让基线被写到别处（于是规则静默变成空扫）。
     */
    private static Path resolveModuleDir() {
        try {
            Path testClasses = Paths.get(ArchitectureRulesTest.class.getProtectionDomain()
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

    private static List<String> readModuleNames(Path backendRootPom) {
        try {
            String xml = Files.readString(backendRootPom, StandardCharsets.UTF_8);
            Matcher matcher = MODULE_TAG.matcher(xml);
            List<String> names = new ArrayList<>();
            while (matcher.find()) {
                names.add(matcher.group(1));
            }
            if (names.isEmpty()) {
                throw new IllegalStateException("在 " + backendRootPom + " 里没有解析到任何 <module>");
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 " + backendRootPom, e);
        }
    }

    /** {@code zxyz-user-service -> user}、{@code zxyz-common -> common}。 */
    private static String expectedTopLevelPackage(String module) {
        String name = module.startsWith("zxyz-") ? module.substring("zxyz-".length()) : module;
        return name.endsWith("-service") ? name.substring(0, name.length() - "-service".length()) : name;
    }
}
