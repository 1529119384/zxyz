package uno.acloud.im.convert;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MapStruct 劫持守护（ISSUE/54 §2.4，46 号 P1-2 收口的回归门禁）：
 * {@code ImEntityMapper} 必须留在独立包 {@code uno.acloud.im.convert}，
 * 不得回到 MyBatis {@code @MapperScan("uno.acloud.im.infrastructure.mapper")} 的同包。
 *
 * <h2>它防的是什么</h2>
 * MyBatis 的 mapper 扫描器不区分接口来源——落在 @MapperScan 包里的 MapStruct 接口
 * 会被一并注册成 MyBatis mapper（同包劫持）：MapStruct 生成实现与 MyBatis MapperFactoryBean
 * 以同名 bean 注册，运行期胜者未定、随时可能炸（「服务起来了」≠「映射正确」）。
 * 46 号已实证此事故并迁包收口，本测试把三个事实钉死：
 * <ul>
 *   <li>源码面：{@code uno.acloud.im} 全树下凡是 {@code org.mapstruct.Mapper} 注解的类，
 *       只允许出现在 {@code .convert} 包；</li>
 *   <li>边界面：{@code @MapperScan} 保持 {@code uno.acloud.im.infrastructure.mapper}
 *       不动，且该包内零 MapStruct 注解；</li>
 *   <li>生成物面：Spring 实际注入的 {@code ImEntityMapperImpl} 全限定名以
 *       {@code .convert.} 为界——注入源错包即劫持复发，无论接口是被搬走还是被重建。</li>
 * </ul>
 * 三处均 <b>fail-closed</b>：探针扫不到文件/生成物时直接断言失败，而不是静默跳过——
 * 一个「找不到就跳过」的守卫等价于没有守卫。
 */
class MapStructHijackGuardTest {

    private static final String CONVERT_PACKAGE = "uno.acloud.im.convert";
    private static final String MYBATIS_SCAN_PACKAGE = "uno.acloud.im.infrastructure.mapper";
    private static final String MAPSTRUCT_IMPL = "ImEntityMapperImpl";

    private static final Pattern PACKAGE_DECL =
            Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern MAPSTRUCT_IMPORT =
            Pattern.compile("^import\\s+org\\.mapstruct\\.Mapper\\s*;", Pattern.MULTILINE);
    private static final Pattern MAPPER_SCAN_DECL =
            Pattern.compile("@MapperScan\\(\\s*\"([^\"]+)\"\\s*\\)");

    // ==========================================================================
    // 门禁
    // ==========================================================================

    @Test
    void mapstructMapper必须只在convert包_防同包劫持回归() {
        Set<String> convertPackages = new LinkedHashSet<>();
        Set<String> foreignPackages = new LinkedHashSet<>();
        int mapperCount = 0;
        for (Path file : mainJavaFiles()) {
            String source = readText(file);
            if (!MAPSTRUCT_IMPORT.matcher(source).find()) {
                continue;
            }
            mapperCount++;
            String pkg = packageOf(file, source);
            if (pkg.equals(CONVERT_PACKAGE)) {
                convertPackages.add(pkg);
            } else {
                foreignPackages.add(pkg + " (" + file.getFileName() + ")");
            }
        }
        assertTrue(mapperCount >= 1, "反空扫：uno.acloud.im 全树 0 个 MapStruct @Mapper"
                + "——扫描探针塌了或 ImEntityMapper 被整体删除，两态都必须红");
        assertTrue(foreignPackages.isEmpty(),
                "MapStruct @Mapper 出现在 convert 包之外: " + foreignPackages
                        + "——落在 @MapperScan 同包即被 MyBatis 扫描器劫持（46 号 P1-2 事故形态），"
                        + "必须移回 " + CONVERT_PACKAGE);
        assertTrue(convertPackages.contains(CONVERT_PACKAGE),
                "convert 包内没有任何 MapStruct @Mapper——ImEntityMapper 迁移回归");
    }

    @Test
    void mapperScan声明必须保持不动且边界包内零mapstruct() {
        String app = readText(applicationPath());
        Matcher matcher = MAPPER_SCAN_DECL.matcher(app);
        assertTrue(matcher.find(), "ZxyzImApplication 上找不到 @MapperScan 声明");
        assertEquals(MYBATIS_SCAN_PACKAGE, matcher.group(1),
                "@MapperScan 值漂移——本测试钉的是「MyBatis 扫描面不含 convert」这一既定边界，"
                        + "若拓扑确需调整，先评估 MapStruct 劫持风险再同步本断言");

        // 边界复检：@MapperScan 目标包内不得出现任何 MapStruct 注解文件
        List<String> leaked = new ArrayList<>();
        for (Path file : mainJavaFiles()) {
            String source = readText(file);
            if (MAPSTRUCT_IMPORT.matcher(source).find()
                    && packageOf(file, source).equals(MYBATIS_SCAN_PACKAGE)) {
                leaked.add(file.getFileName().toString());
            }
        }
        assertTrue(leaked.isEmpty(),
                "@MapperScan 包 " + MYBATIS_SCAN_PACKAGE + " 内混入 MapStruct 注解: " + leaked
                        + "——同包劫持已成立，立即迁回 " + CONVERT_PACKAGE);
    }

    @Test
    void 注入源ImEntityMapperImpl的全限定名必须以convert为界() {
        // MapStruct 注解处理器在 compile 阶段生成实现源码；mvn test 生命周期先编译再测试，
        // 因此该目录必然存在。若缺失 = 注解处理被关或实现类没生成，Spring 注入会直接崩，
        // 属于必须红的两态之一。
        Path impl = generatedSourcePath().resolve(
                CONVERT_PACKAGE.replace('.', '/') + "/" + MAPSTRUCT_IMPL + ".java");
        assertTrue(Files.isRegularFile(impl),
                "找不到生成的 " + MAPSTRUCT_IMPL + ": " + impl
                        + "——先跑 mvn compile（MapStruct 注解处理可能被关闭，或接口已不在 convert 包）");

        String source = readText(impl);
        Matcher matcher = PACKAGE_DECL.matcher(source);
        assertTrue(matcher.find(), "生成物 " + MAPSTRUCT_IMPL + " 解析不到 package 声明");
        assertEquals(CONVERT_PACKAGE, matcher.group(1),
                "Spring 注入的 " + MAPSTRUCT_IMPL + " 全限定名不在 " + CONVERT_PACKAGE
                        + "（实得 " + matcher.group(1) + "）——注入源错包即劫持复发："
                        + "与 MyBatis MapperFactoryBean 同名 bean 竞争，胜者未定");
        assertTrue(source.contains("implements ImEntityMapper"),
                "生成物 " + MAPSTRUCT_IMPL + " 不再实现 ImEntityMapper——映射器签名漂移，请同步本守护");
    }

    // ==========================================================================
    // 扫描工具
    // ==========================================================================

    private static List<Path> mainJavaFiles() {
        Path root = moduleDir().resolve("src/main/java/uno/acloud/im");
        if (!Files.isDirectory(root)) {
            throw new AssertionError("找不到主源码树: " + root);
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(!files.isEmpty(), "主源码树反空扫失败：0 个源文件");
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String packageOf(Path file, String source) {
        Matcher matcher = PACKAGE_DECL.matcher(source);
        assertTrue(matcher.find(), file + " 解析不到 package 声明");
        return matcher.group(1);
    }

    private static Path applicationPath() {
        return moduleDir().resolve("src/main/java/uno/acloud/im/ZxyzImApplication.java");
    }

    private static Path generatedSourcePath() {
        return moduleDir().resolve("target/generated-sources/annotations");
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 " + file + " 失败", e);
        }
    }

    private static Path moduleDir() {
        try {
            Path testClasses = Paths.get(MapStructHijackGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            Path dir = testClasses.getParent().getParent();
            if (dir == null || !Files.isRegularFile(dir.resolve("pom.xml"))) {
                throw new IllegalStateException("反推出的模块目录里没有 pom.xml: " + dir);
            }
            return dir;
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("无法解析测试类的 class 输出位置", e);
        }
    }
}
