package uno.acloud.admin.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * ZXYZ 后端【内部端点契约门禁】—— 覆盖 {@code /api/internal/**}（以及 {@code /api/email/internal}）这类
 * <b>没有前端消费者、只被其它微服务调用</b>的端点。
 *
 * <h2>为什么只钉「内部端点」</h2>
 * 前端消费的端点有前端五道门禁兜底（组件测试 / `vue-tsc` / 路由守卫单测 / 构建产物体积），
 * 一旦路径或字段名改坏，前端测试立刻红。而<b>内部端点没有任何这类保护</b>：
 * 调用方是 {@code zxyz-starter} 或各服务里的 {@code *Client}，它们不参与前端门禁，
 * 而现有 {@code InternalFileControllerTest} 之类的用例是「直接 new controller」的行为单测 ——
 * 把 {@code @PostMapping("/check")} 改成 {@code @PostMapping("/verify")} 这种改动<b>一条都不会红</b>，
 * 直到线上调用方拿到 404 或字段静默为 {@code null}。
 * 本测试补上的正是这一层：把「HTTP 契约」本身（方法 / 路径 / 请求体字段名）钉成快照，
 * 任何改动都会让 CI 出现 diff 并失败，逼改动者<b>显式确认这是一次跨服务契约变更</b>。
 *
 * <h2>钉的是什么（以及刻意不钉什么）</h2>
 * 每个端点记录四项：
 * <ul>
 *   <li>{@code method} + {@code path}：调用方拼错就是 404，是最硬的契约；</li>
 *   <li>{@code requestBodyType} + {@code requestBodyJsonFields}：字段名即 JSON key，改名会让调用方
 *       <b>静默传 null</b>（不报错），必须钉；</li>
 *   <li>{@code responseDataType}：记录 {@code Result<T>} 里的 {@code T}。它更多是「提醒 review」而非
 *       硬约束 —— 换 DTO 类型也会让 diff 出现，同样需要人工确认。</li>
 * </ul>
 * 刻意<b>不</b>钉校验注解、`@Operation` 摘要、参数顺序等非契约信息，避免噪音让门禁失去信号。
 *
 * <h2>⚠️ 使用约束：必须全量 reactor 构建（fail-closed）</h2>
 * 与 {@code ArchitectureRulesTest} 同一原因（本仓没有任何模块能在 classpath 上看到全部服务），
 * 本测试直读各模块的 {@code target/classes}，<b>不能</b>用 {@code mvn -pl <单模块> [-am] test} 触发 ——
 * 那只会编译被选中的模块。模块目录缺失时直接失败并打印缺失清单，绝不「扫不到就当通过」。
 * <pre>
 *   cd ZXYZdatabaseBack &amp;&amp; mvn -B test                       # CI 跑的就是这一条
 *   # 或先全量编译，再只重跑本模块：
 *   mvn -B -DskipTests install &amp;&amp; mvn -B -pl zxyz-admin-service test
 * </pre>
 * 测试放在 reactor 的<b>最后一个模块</b> {@code zxyz-admin-service}（没有任何模块依赖它，
 * 全量构建中它最后执行，此时其余模块的 target/classes 都已就绪）。
 *
 * <h2>快照文件的纪律</h2>
 * <ul>
 *   <li>快照：{@code zxyz-admin-service/src/test/resources/contract/internal-api-contract.json}
 *       （<b>必须提交进版本库</b>；路径按绝对路径指定，不依赖进程工作目录）。</li>
 *   <li><b>常规运行</b>：快照文件缺失 = 门禁被拿走 ⇒ <b>直接失败</b>（fail-closed），
 *       提示用下面的 bootstrap 命令生成，而不是「悄悄按当前代码重新冻结」。这是刻意的：
 *       <b>删掉快照就是放宽门禁，必须响亮失败。</b></li>
 *   <li><b>首次落地 / 确需变更契约</b>：{@code mvn -B -pl zxyz-admin-service test -Dcontract.bootstrap=true}
 *       重新生成（内容会打印到测试输出），然后<b>在提交信息里说明为什么契约发生变化</b>、
 *       并同步修改所有调用方。这是这套门禁唯一容易被滥用的地方，请自觉。</li>
 * </ul>
 */
class InternalApiContractTest {

    /** 快照文件（相对本模块根）。与 ArchitectureRulesTest 一样用绝对路径，避免 surefire/IDE 工作目录差异。 */
    private static final String CONTRACT_RELATIVE_TO_MODULE = "src/test/resources/contract/internal-api-contract.json";

    private static final Pattern MODULE_TAG = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>");

    /** 内部命名空间的判据：路径里出现名为 {@code internal} 的一段（覆盖 /api/internal/** 与 /api/email/internal）。 */
    private static final String INTERNAL_PATH_SEGMENT = "internal";

    /**
     * 扫描到的端点数下限。2026-09-15 实测 <b>90</b> 个内部端点（分布在 83 条不同路径上）；
     * 2026-09-22 复测为 <b>92</b>（新增项目存储用量内部端点 + 4 条既有端点补 {@code projectIds} 字段）。
     * ⚠️ 这个数字<b>随功能增长，不是固定值</b>：这里只做「下限不得低于 80」的防空扫断言，
     * 精确的端点集合以快照文件 {@code contract/internal-api-contract.json} 为准，别照抄本注释的历史读数。
     * ⚠️ 它比 `ISSUE/18` 里「40+ 内部端点无契约测试」的估计高出一倍多 —— 因为那条估计
     * 只数了「没有测试的 Controller」，没有把内部 Controller 的每个方法展开。阈值取 80 留余量。
     */
    private static final int MIN_EXPECTED_ENDPOINTS = 80;

    /** 不同路径数下限（防「少数 Controller 反复展开」造成的假绿灯）。实测 83。 */
    private static final int MIN_EXPECTED_DISTINCT_PATHS = 75;

    /** 调用方源码里应扫到的内部路径字面量下限（防扫描逻辑失效造成的空扫）。实测值见断言信息。 */
    private static final int MIN_EXPECTED_CALL_SITES = 20;

    /** 源码里的字符串字面量（不跨行）。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"\\n]*)\"");

    /** 路径变量与格式化占位：{@code {userId}}、{@code {id:[0-9]+}}、{@code %s}、{@code %d}。 */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}|%[sd]");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path moduleDir;
    private static List<String> reactorModules;
    private static ClassLoader serviceClassLoader;
    private static List<Endpoint> actualEndpoints;
    private static List<String> loadFailures;

    /** 一个内部端点的契约快照。字段顺序即 JSON 里的键顺序（保证生成结果可复现）。 */
    private record Endpoint(String method,
                            String path,
                            String requestBodyType,
                            List<String> requestBodyJsonFields,
                            String responseDataType) {
    }

    @BeforeAll
    static void scanEveryReactorModule() {
        moduleDir = resolveModuleDir();
        Path backendRoot = moduleDir.getParent();
        reactorModules = readModuleNames(backendRoot.resolve("pom.xml"));

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
            sb.append("内部端点契约测试要求【全量 reactor 构建】，但有 ").append(missing.size())
                    .append(" 个模块的 target/classes 不存在：\n");
            for (Path path : missing) {
                sb.append("  - ").append(path).append('\n');
            }
            sb.append("""

                    原因：契约门禁是跨模块的（任何单个模块的 classpath 都看不到全部服务），
                    所以本测试直接读取各模块的 target/classes —— 它不能被 `mvn -pl <单模块> [-am] test` 触发。
                    正确做法（二选一）：
                      • 在 ZXYZdatabaseBack 根目录执行全量测试：  mvn -B test
                      • 先全量编译再只跑本模块：                  mvn -B -DskipTests install && mvn -B -pl zxyz-admin-service test
                    """);
            throw new IllegalStateException(sb.toString());
        }

        serviceClassLoader = new URLClassLoader(toUrls(present), InternalApiContractTest.class.getClassLoader());

        loadFailures = new ArrayList<>();
        List<Class<?>> controllers = loadControllerClasses(present);
        actualEndpoints = collectEndpoints(controllers);
    }

    // ==================================================================================
    // 门禁 1：主门禁 —— 与入库快照逐字比对
    // ==================================================================================

    @Test
    void internal_api_contract_must_match_checked_in_snapshot() throws IOException {
        String actual = renderContract(actualEndpoints);
        Path contractFile = moduleDir.resolve(CONTRACT_RELATIVE_TO_MODULE).toAbsolutePath().normalize();

        if (Boolean.getBoolean("contract.bootstrap")) {
            Files.createDirectories(contractFile.getParent());
            Files.writeString(contractFile, actual, StandardCharsets.UTF_8);
            throw new AssertionError("已按当前代码重新生成契约快照: " + contractFile
                    + "\n共 " + actualEndpoints.size() + " 个内部端点。"
                    + "\n请用 `git diff` 逐条确认这些改动是【有意的跨服务契约变更】，"
                    + "同步修改全部调用方后一并提交，并在提交信息里说明变更原因。");
        }

        assertThat(contractFile)
                .as("内部端点契约快照必须提交进版本库（%s）。"
                        + "它缺失时本门禁形同不存在，因此这里刻意 fail-closed —— "
                        + "若确需首次生成，请运行: mvn -B -pl zxyz-admin-service test -Dcontract.bootstrap=true",
                        contractFile)
                .exists();

        // 行尾归一化：本地 Windows 工作区是 CRLF，入库/CI 是 LF —— 只比较内容，不比较行尾。
        String expected = normalizeNewlines(Files.readString(contractFile, StandardCharsets.UTF_8));

        // 刻意不用 assertThat(...).isEqualTo(...) —— AssertJ 会把整份契约（90 个端点的 JSON）
        // 完整打印两遍到 CI 日志，反而淹没真正的差异。这里只报首处差异。
        if (!expected.equals(actual)) {
            fail("""
                    内部端点 HTTP 契约发生了变化。这些端点没有前端消费者，改了不会在前端门禁里报错，
                    但调用方（zxyz-starter / 各服务的 *Client）会拿到 404 或字段静默为 null。
                    请确认这是【有意的跨服务契约变更】，同步修改全部调用方后，再用
                      mvn -B -pl zxyz-admin-service test -Dcontract.bootstrap=true
                    重新生成快照并提交（并在提交信息里说明变更原因）。%s""",
                    diffHint(expected, actual));
        }
    }

    // ==================================================================================
    // 门禁 2：自检 —— 保证「发现端点」的机制本身没有静默失效
    // ==================================================================================

    @Test
    void endpoints_are_internally_consistent() {
        assertThat(actualEndpoints)
                .as("扫到的内部端点数量明显偏少，说明 target/classes 的解析或类加载出了问题"
                        + "（宁可响亮失败，也不要空扫）")
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_ENDPOINTS);

        Set<String> duplicateKeys = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        for (Endpoint endpoint : actualEndpoints) {
            if (!seen.add(endpoint.method() + " " + endpoint.path())) {
                duplicateKeys.add(endpoint.method() + " " + endpoint.path());
            }
        }
        assertThat(duplicateKeys)
                .as("同一个 方法+路径 被注册了两次：Spring 启动时会直接报 ambiguous mapping，"
                        + "但依赖自动装配的扫描发现不了，必须在这里拦住")
                .isEmpty();

        assertThat(loadFailures)
                .as("有 Controller 类无法从任意模块的 target/classes 加载出来。"
                        + "这通常意味着类引用了某个模块没有声明的依赖 —— 那它上线时也会炸，"
                        + "不能只在测试里被静默跳过")
                .isEmpty();
    }

    @Test
    void contract_covers_only_the_internal_namespace_and_spans_many_controllers() {
        Set<String> distinctPaths = actualEndpoints.stream()
                .map(Endpoint::path)
                .collect(TreeSet::new, Set::add, Set::addAll);
        assertThat(distinctPaths)
                .as("内部端点只落在 %d 条不同的路径上，疑似大量 Controller 未被扫到", distinctPaths.size())
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_DISTINCT_PATHS);

        assertThat(actualEndpoints)
                .as("契约只收录 internal 命名空间下的端点（前端消费的端点由前端五道门禁保护，不在此处重复钉）")
                .allSatisfy(endpoint -> assertThat(isInternalPath(endpoint.path()))
                        .as("非内部路径被误收进契约: %s", endpoint.path())
                        .isTrue());
    }

    /**
     * Jackson 的字段名 → JSON key 映射自检。
     *
     * <p>本契约用「Java 字段名」当作 JSON key，这只在<b>全局没有开启命名策略</b>时成立。
     * 若将来有人配了 {@code spring.jackson.property-naming-strategy: SNAKE_CASE}，
     * 契约文件里的字段名就<b>不再等于</b>真实 JSON key，门禁会变成「看起来在保护、其实保护错了名字」的假绿灯。
     * 所以这里把它钉住：一旦启用，本用例失败并提示重新生成契约（届时生成逻辑也需要改成按 ObjectMapper 命名）。
     */
    @Test
    void jackson_naming_strategy_must_stay_default() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String module : reactorModules) {
            for (String configFile : List.of("application.yml", "application-common.yml",
                    "application-dev.yml", "application-prod.yml", "application-test.yml")) {
                Path path = moduleDir.getParent().resolve(module).resolve("target").resolve("classes").resolve(configFile);
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("#") || !trimmed.contains("property-naming-strategy")) {
                        continue;
                    }
                    if (trimmed.matches("property-naming-strategy:\\s*(null|)$")) {
                        continue;
                    }
                    offenders.add(module + "/" + configFile + ": " + trimmed);
                }
            }
        }
        assertThat(offenders)
                .as("检测到 Jackson 全局命名策略被启用。契约快照里的字段名是按「Java 字段名」记录的，"
                        + "启用命名策略后它不再等于真实 JSON key —— 必须同步修改本测试的字段名推导逻辑并重新生成快照")
                .isEmpty();
    }

    // ==================================================================================
    // 门禁 4：调用方 ↔ 提供方 对账（这才是「契约」二字的正解）
    // ==================================================================================

    /**
     * 调用方源码里硬编码的内部路径，必须真的存在对应的提供方端点。
     *
     * <p>上面三个门禁是「提供方快照」——它们能发现<b>路径被改</b>，但如果某条路径从一开始就写错了
     * （调用方写 {@code /api/internal/users/{id}／quota}、提供方实际是 {@code /quota} 之类），
     * 快照门禁只会忠实地把错误版本冻结下来。本门禁补上另一半：把
     * {@code zxyz-starter}、{@code zxyz-common}、各服务 {@code *Client} 里写死的路径字面量
     * 与提供方端点集合对账，<b>对不上就是必然 404</b>。
     *
     * <p>对账口径（刻意选「规范化后精确相等」而不是模糊匹配）：
     * 把两侧的路径变量（{@code {userId}}、{@code {id:[0-9]+}}）与格式化占位（{@code %s} / {@code %d}）
     * 统一替换成 {@code {}} 后做<b>字符串全等</b>。这样 {@code .../users/{id}} 与
     * {@code .../users/{userId}} 视为同一条（变量名不属契约），而
     * {@code .../users/search} 不会被误判成 {@code .../users/{id}}。
     *
     * <p>已知的刻意跳过项：含 {@code *} 的通配条目（如内部端点白名单里的 {@code /api/internal/**}）
     * 无法逐条对账；{@code *Controller.java} 文件整体跳过（它的<b>类级</b> {@code @RequestMapping}
     * 只提供路径前缀，不是一个可调用的端点，参与了必然误报）。
     */
    @Test
    void every_internal_path_hardcoded_by_a_caller_must_exist_on_the_provider_side() {
        Set<String> providedPaths = actualEndpoints.stream()
                .map(endpoint -> normalizePathTemplate(endpoint.path()))
                .collect(TreeSet::new, Set::add, Set::addAll);

        List<CallSite> callSites = scanCallerPathLiterals();
        assertThat(callSites)
                .as("在源码里一个内部端点调用都没扫到 —— 扫描逻辑失效了（宁可响亮失败，也不要空扫）")
                .hasSizeGreaterThanOrEqualTo(MIN_EXPECTED_CALL_SITES);

        List<String> unmatched = new ArrayList<>();
        for (CallSite site : callSites) {
            if (!providedPaths.contains(normalizePathTemplate(site.rawPath()))) {
                unmatched.add(site.rawPath() + "    ← " + site.file() + ":" + site.line());
            }
        }

        assertThat(unmatched)
                .as("""
                        以下路径被调用方写死在源码里，但没有任何内部端点提供它 ——
                        调用时必然是 404（内部端点不挂在网关的对外路由上，前端不会先发现）。
                        要么是 Controller 改了路径却没同步调用方，要么是调用方本来就写错了。
                        修的时候请两边一起改，并同步更新契约快照。""")
                .isEmpty();
    }

    /** 一个「在源码里写死了内部路径」的位置。 */
    private record CallSite(String file, int line, String rawPath) {
    }

    private static List<CallSite> scanCallerPathLiterals() {
        List<CallSite> sites = new ArrayList<>();
        for (String module : reactorModules) {
            Path sourceRoot = moduleDir.getParent().resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> javaFiles;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                javaFiles = walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .toList();
            } catch (IOException e) {
                throw new UncheckedIOException("无法遍历 " + sourceRoot, e);
            }
            for (Path file : javaFiles) {
                if (file.getFileName().toString().endsWith("Controller.java")) {
                    continue;
                }
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("无法读取 " + file, e);
                }
                for (int index = 0; index < lines.size(); index++) {
                    Matcher matcher = STRING_LITERAL.matcher(lines.get(index));
                    while (matcher.find()) {
                        String literal = matcher.group(1);
                        if (!literal.contains("/api/internal/") && !literal.contains("/api/email/internal")) {
                            continue;
                        }
                        if (literal.contains("*")) {
                            continue;
                        }
                        int queryStart = literal.indexOf('?');
                        String path = queryStart >= 0 ? literal.substring(0, queryStart) : literal;
                        // 以 '/' 结尾说明后面还拼了变量（如 getJson("/api/internal/users/" + userId)）。
                        // 这种前缀本身不构成一条路径，无法静态对账 —— 必须跳过，
                        // 否则每一条拼接调用都会被误报成「提供方不存在」。
                        if (path.isEmpty() || path.endsWith("/")) {
                            continue;
                        }
                        sites.add(new CallSite(file.getFileName().toString(), index + 1, path));
                    }
                }
            }
        }
        return sites;
    }

    /** 把路径变量与格式化占位统一成 {@code {}}，使「变量名不同」不被当成契约差异。 */
    private static String normalizePathTemplate(String path) {
        return PATH_VARIABLE.matcher(path).replaceAll("{}");
    }

    // ==================================================================================
    // 核心逻辑：扫描 → 反射 → 生成
    // ==================================================================================

    /** 扫各模块 target/classes 下的 {@code *Controller.class}（controller 包内），并加载（不初始化）。 */
    private static List<Class<?>> loadControllerClasses(List<Path> classesDirs) {
        List<String> classNames = new ArrayList<>();
        for (Path classesDir : classesDirs) {
            try {
                Files.walkFileTree(classesDir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String fileName = file.getFileName().toString();
                        if (!fileName.endsWith("Controller.class") || fileName.contains("$")) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relative = classesDir.relativize(file).toString().replace('\\', '/');
                        if (!relative.contains("/controller/")) {
                            return FileVisitResult.CONTINUE;
                        }
                        classNames.add(relative.substring(0, relative.length() - ".class".length())
                                .replace('/', '.'));
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException("无法遍历 " + classesDir, e);
            }
        }

        List<Class<?>> loaded = new ArrayList<>();
        for (String name : classNames) {
            try {
                // initialize=false：只读注解，不触发静态初始化
                loaded.add(Class.forName(name, false, serviceClassLoader));
            } catch (ClassNotFoundException | LinkageError e) {
                loadFailures.add(name + " → " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return loaded;
    }

    /** 用 Spring 自己的注解读取逻辑（{@link AnnotatedElementUtils}）保证与 Spring MVC 的语义完全一致。 */
    private static List<Endpoint> collectEndpoints(List<Class<?>> controllers) {
        List<Endpoint> endpoints = new ArrayList<>();
        for (Class<?> controller : controllers) {
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            if (classMapping == null) {
                continue;
            }
            List<String> classPaths = mappingPaths(classMapping);
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                RequestMapping methodMapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                for (String classPath : classPaths) {
                    for (String methodPath : mappingPaths(methodMapping)) {
                        String fullPath = normalizePath(classPath + "/" + methodPath);
                        if (!isInternalPath(fullPath)) {
                            continue;
                        }
                        List<String> methods = mappingHttpMethods(methodMapping);
                        Class<?> bodyType = requestBodyType(method);
                        for (String httpMethod : methods) {
                            endpoints.add(new Endpoint(
                                    httpMethod,
                                    fullPath,
                                    bodyType == null ? null : bodyType.getTypeName(),
                                    jsonFieldNames(bodyType),
                                    responseDataType(method)));
                        }
                    }
                }
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
        return endpoints;
    }

    /**
     * 取 {@code @RequestMapping} 上的路径。
     *
     * <p>{@code value} 与 {@code path} 是互为 {@code @AliasFor} 的别名。实测经
     * {@link AnnotatedElementUtils#findMergedAnnotation} 合成后<b>两个属性都会被填充且内容相同</b>——
     * 若把两者简单 concat，类级与方法级各贡献 2 份 ⇒ 组合数被放大 <b>4 倍</b>
     * （曾因此把 90 个真实端点算成 356 个，并被上面的重复检测抓到）。
     * 因此这里取<b>两个属性的并集并去重</b>，不依赖任何别名语义假设：
     * 两者相同则得到 1 条，只有一个非空也得到 1 条；都空表示映射到类根路径。
     */
    private static List<String> mappingPaths(RequestMapping mapping) {
        Set<String> collected = new LinkedHashSet<>();
        Arrays.stream(mapping.path()).filter(p -> p != null && !p.isEmpty()).forEach(collected::add);
        Arrays.stream(mapping.value()).filter(p -> p != null && !p.isEmpty()).forEach(collected::add);
        return collected.isEmpty() ? List.of("") : List.copyOf(collected);
    }

    /**
     * {@code @GetMapping} 等是 {@code @RequestMapping(method = ...)} 的元注解，{@code findMergedAnnotation}
     * 会合成出 {@code method} 属性；两者都没写时 Spring 默认接受<b>全部</b>方法（此处与 Spring 一致地展开）。
     */
    private static List<String> mappingHttpMethods(RequestMapping mapping) {
        if (mapping.method().length == 0) {
            return List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");
        }
        return Arrays.stream(mapping.method()).map(Enum::name).distinct().sorted().toList();
    }

    private static Class<?> requestBodyType(Method method) {
        Type[] parameterTypes = method.getGenericParameterTypes();
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && i < parameterTypes.length; i++) {
            if (!AnnotatedElementUtils.hasAnnotation(parameters[i], RequestBody.class)) {
                continue;
            }
            Type type = parameterTypes[i];
            if (type instanceof Class<?> clazz) {
                return clazz;
            }
            if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
                return raw;
            }
        }
        return null;
    }

    /** {@code Result<T>} → {@code T} 的类型名；非 Result 或原始类型直接取原样。 */
    private static String responseDataType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType parameterized
                && parameterized.getRawType() instanceof Class<?> raw
                && "uno.acloud.common.Result".equals(raw.getTypeName())) {
            return parameterized.getActualTypeArguments()[0].getTypeName();
        }
        return returnType.getTypeName();
    }

    /**
     * 请求体的 JSON key 集合。
     *
     * <p>全局未启用命名策略（见 {@link #jackson_naming_strategy_must_stay_default()}），
     * 因此 JSON key = {@code @JsonProperty} 指定的名字，否则就是字段名本身。
     * {@code Map} / 基础类型无法枚举字段 ⇒ 返回空列表（契约里体现为「不钉字段名」）。
     */
    private static List<String> jsonFieldNames(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isEnum()
                || type == String.class || Number.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type) || Iterable.class.isAssignableFrom(type)) {
            return List.of();
        }
        Set<String> names = new TreeSet<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                names.add(jsonNameOf(type, field));
            }
        }
        return List.copyOf(names);
    }

    private static String jsonNameOf(Class<?> owner, Field field) {
        JsonProperty onField = field.getAnnotation(JsonProperty.class);
        if (onField != null && !onField.value().isEmpty()) {
            return onField.value();
        }
        // record：@JsonProperty 也可能只标在组件上
        if (owner.isRecord()) {
            for (java.lang.reflect.RecordComponent component : owner.getRecordComponents()) {
                if (component.getName().equals(field.getName())) {
                    JsonProperty onComponent = component.getAnnotation(JsonProperty.class);
                    if (onComponent != null && !onComponent.value().isEmpty()) {
                        return onComponent.value();
                    }
                }
            }
        }
        return field.getName();
    }

    private static boolean isInternalPath(String path) {
        return Arrays.asList(path.split("/")).contains(INTERNAL_PATH_SEGMENT);
    }

    /** 拼接多段路径并规范化多余斜杠；保留 {@code {id}} 占位符原样（它本身就是契约的一部分）。 */
    private static String normalizePath(String raw) {
        String normalized = raw.replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    // ==================================================================================
    // 渲染与比对
    // ==================================================================================

    /** 渲染成稳定的 JSON 文本：端点已按 path+method 排序，字段顺序由 record 决定。 */
    private static String renderContract(List<Endpoint> endpoints) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", endpoint.method());
            row.put("path", endpoint.path());
            row.put("requestBodyType", endpoint.requestBodyType());
            row.put("requestBodyJsonFields", endpoint.requestBodyJsonFields());
            row.put("responseDataType", endpoint.responseDataType());
            rows.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("endpoints", rows);
        // 行尾统一为 \n：worktree 在 Windows 上是 CRLF，直接写出去会让 CI（LF）每次都比出 diff。
        return normalizeNewlines(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)) + "\n";
    }

    private static String normalizeNewlines(String text) {
        return text.replace("\r\n", "\n");
    }

    /** 只打印首处差异附近的行，避免把整份契约刷进 CI 日志。 */
    private static String diffHint(String expected, String actual) {
        List<String> expectedLines = Arrays.asList(expected.split("\n", -1));
        List<String> actualLines = Arrays.asList(actual.split("\n", -1));
        int limit = Math.max(expectedLines.size(), actualLines.size());
        for (int i = 0; i < limit; i++) {
            String left = i < expectedLines.size() ? expectedLines.get(i) : "<缺失>";
            String right = i < actualLines.size() ? actualLines.get(i) : "<新增>";
            if (!left.equals(right)) {
                return "\n首个差异在第 " + (i + 1) + " 行:\n  快照: " + left + "\n  当前: " + right;
            }
        }
        return "";
    }

    // ==================================================================================
    // 工具（与 ArchitectureRulesTest 同源：模块定位 / 模块清单）
    // ==================================================================================

    private static URL[] toUrls(List<Path> paths) {
        return paths.stream().map(path -> {
            try {
                return path.toUri().toURL();
            } catch (MalformedURLException e) {
                throw new UncheckedIOException("无法把 " + path + " 转成 URL", e);
            }
        }).toArray(URL[]::new);
    }

    private static Path resolveModuleDir() {
        try {
            Path testClasses = Paths.get(InternalApiContractTest.class.getProtectionDomain()
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
}
