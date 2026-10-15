package uno.acloud.common.mq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 配套门禁（ISSUE/54 §2.3）：MQ 包测试代码<b>禁止 mock ObjectMapper</b>。
 *
 * <h2>它防的是什么（51 号 P0 的结构性条件）</h2>
 * 51 号 P0（运行时缺 jsr310 丢 17h 审计）能藏这么久，靠的是 6 个 MQ 消费者测试把
 * {@code ObjectMapper} 整个 {@code @Mock}——事件反序列化能力零验证，事件 record
 * 加 LocalDateTime 字段时全链静默炸。mock 掉 mapper 的消费者测试给不了任何
 * 「反序列化还活着」的证据，这是「测试补上运行时缺的东西」这类假证据的结构性温床。
 * 本门禁把它钉成仓库级不变量：<b>mq 包下的测试文件不得出现
 * {@code @Mock}/{@code @MockitoBean} 的 ObjectMapper（任何 Jackson 轨），也不得 {@code mock(ObjectMapper.class)}</b>；
 * 要验证反序列化行为就用真实 mapper（{@code new ObjectMapper().registerModule(new JavaTimeModule())}
 * 或 {@code @Spy} 真实例）。
 *
 * <h2>怎么扫（与 AloneRedisWiringContractTest 同款零依赖文件扫描）</h2>
 * 从本模块目录反推后端根，遍历根 pom {@code <modules>} 登记的每个模块的
 * {@code src/test/java}，凡 <b>mq 包</b>（路径含 {@code /mq/} 段或包声明以 {@code .mq} 段收尾/包含）
 * 的测试文件，检测两种违规形态并点名报告。只看磁盘源码文本，不加载任何类，
 * 因此放在依赖最少的 zxyz-common 即可运行。
 *
 * <h2>为什么是「豁免名单」而不是「直接红」</h2>
 * 存量 6 处违规（2026-10-11 盘点，文件级清单见 {@link #GRANDFATHERED_MOCK_MAPPER_TESTS}）
 * 的<b>修复权不在本批次</b>（归 file/project/share/team 各服务代理与 Lead），
 * 直接全量红会让 C1 门禁自落地起就挡住所有人的合并信号。因此采用本仓
 * AloneRedisWiringContractTest 的既有模式：<b>显式豁免 + 两条反向断言</b>——
 * <ol>
 *   <li>新违规（不在名单里）⇒ 红，点名文件与违规形态；</li>
 *   <li><b>陈旧豁免</b>：名单里的文件已被修复（不再违规）⇒ 红，要求把条目从名单移除——
 *       否则名单会变成「关于现状的谎言」；<b>各服务代理修复存量违规后，必须同步删掉本名单对应条目</b>；</li>
 *   <li>名单含不存在的文件 ⇒ 红（防改名/删除让条目静默失效）。</li>
 * </ol>
 *
 * <h2>反空扫与自检</h2>
 * 扫描到的 mq 测试文件数有下界（{@link #MIN_MQ_TEST_FILES}）；G1 自检把检查逻辑喂给
 * 已知违规/已知合规/已知盲区样本，证明它<b>真的会失败</b>且不会误报。
 */
class MqConsumerTestDeserializationGuardTest {

    // ==========================================================================
    // 口径常量
    // ==========================================================================

    /**
     * 扫描下界：当前（2026-10-11）实测 mq 包测试文件 6 个
     * （file 1 / project 2 / share 2 / team 1；im 的 mq 测试目录为空）。
     * 低于下界说明扫描面塌了（模块改名/目录迁移），宁可响亮失败。
     */
    private static final int MIN_MQ_TEST_FILES = 6;

    /**
     * 视为「mock 掉 ObjectMapper」的注解（Mockito 字段注入 + Boot 的 @MockitoBean）。
     */
    private static final Set<String> MOCK_FIELD_ANNOTATIONS = Set.of("@Mock", "@MockitoBean");

    /**
     * 存量违规豁免名单（2026-10-11 全仓盘点，{@code @Mock ObjectMapper} 实证 6 处）。
     * <p>格式：{@code 模块名 → 测试文件相对模块根的路径}。</p>
     * <p><b>⚠️ 修复权归属</b>：修复归各服务代理/Lead（54 号批次分工），不归本门禁；
     * 修复完成（改为真实 mapper）后<b>必须</b>从此处移除对应条目，否则陈旧豁免断言会红。</p>
     * <p><b>2026-10-15 更新（60 号审计 🟠-4/🟡-5）</b>：file/project/share 三份
     * {@code UserDeletedEventConsumerTest} 已改为真实 mapper（消费骨架收敛到
     * {@code MqUserDeletedEventConsumerTemplate}），条目按门禁要求移除。</p>
     */
    private static final Map<String, Set<String>> GRANDFATHERED_MOCK_MAPPER_TESTS = Map.of(
            "zxyz-project-service", Set.of(
                    "src/test/java/uno/acloud/project/mq/StorageCacheInvalidationConsumerTest.java"),
            "zxyz-share-service", Set.of(
                    "src/test/java/uno/acloud/share/mq/UserProfileUpdatedEventConsumerTest.java"));
    // 历史：2026-10-11 盘点时本名单还含 zxyz-team-service 的 UserDeletedEventConsumerTest
    // （team 代理当轮并行修复为真实 mapper 后，陈旧豁免断言自动变红，条目已按门禁要求移除）。
    // 这正是「陈旧豁免反向断言」按设计工作的第一个实例。

    // ==========================================================================
    // 检测模式
    // ==========================================================================

    /** ObjectMapper 字段/变量声明：捕获变量名与声明结束符（分号或等号）。 */
    private static final Pattern OBJECT_MAPPER_DECLARATION =
            Pattern.compile("\\bObjectMapper\\s+(\\w+)\\s*(;|=)");

    /** Mockito 内联静态 mock：mock(ObjectMapper.class)。 */
    private static final Pattern INLINE_MOCK_MAPPER =
            Pattern.compile("\\bmock\\s*\\(\\s*ObjectMapper\\s*\\.class");

    /** 单行注释。 */
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");
    /** 块注释（含 javadoc）。 */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 包声明。 */
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    // ==========================================================================
    // 数据模型
    // ==========================================================================

    /** 一处违规：文件、违规形态、行号（1 起）。 */
    record Violation(String module, Path file, String kind, int line) {

        @Override
        public String toString() {
            return module + " → " + file.getFileName() + " 第 " + line + " 行 [" + kind + "]";
        }
    }

    // ==========================================================================
    // 主门禁
    // ==========================================================================

    @Test
    void mq包测试禁止mockObjectMapper_新违规必须红_存量违规必须登记在豁免名单() {
        Path backendRoot = backendRoot();
        List<Path> mqTestFiles = scanMqTestFiles(backendRoot);

        if (mqTestFiles.size() < MIN_MQ_TEST_FILES) {
            throw new AssertionError("门禁反空扫失败：全仓只扫到 " + mqTestFiles.size()
                    + " 个 mq 包测试文件（下界 " + MIN_MQ_TEST_FILES + "，2026-10-11 实测 6）。"
                    + "要么模块/目录结构变更使扫描面塌了，要么 mq 测试被整体删除——"
                    + "两者都让本门禁恒绿，比不写还危险。扫到的文件：\n  - "
                    + String.join("\n  - ", mqTestFiles.stream().map(p -> p.toString()).toList()));
        }

        Map<String, List<Violation>> violationsByModule = new LinkedHashMap<>();
        for (Path file : mqTestFiles) {
            String module = moduleOf(backendRoot, file);
            List<Violation> found = detectMockObjectMapper(module, file, readText(file));
            if (!found.isEmpty()) {
                violationsByModule.put(module + " → " + file.getFileName(), found);
            }
        }

        // 规则 1：新违规（不在豁免名单）⇒ 红
        List<Violation> freshViolations = new ArrayList<>();
        for (List<Violation> violations : violationsByModule.values()) {
            for (Violation violation : violations) {
                if (!isGrandfathered(violation)) {
                    freshViolations.add(violation);
                }
            }
        }
        if (!freshViolations.isEmpty()) {
            throw new AssertionError("【MQ 消费者测试反序列化能力零验证】以下 mq 包测试文件 mock 了 ObjectMapper：\n  - "
                    + String.join("\n  - ", freshViolations.stream().map(Violation::toString).sorted().toList())
                    + "\n为什么不能放过：mock 掉 mapper 后，事件反序列化能力零验证——"
                    + "事件 record 加 LocalDateTime/Instant 字段时（51 号 P0 同型），全链静默炸。"
                    + "\n修法：用真实 mapper（new ObjectMapper().registerModule(new JavaTimeModule())，"
                    + "或 @Spy 包真实实例）重写消费端测试，让反序列化真实发生；"
                    + "_wire 契约本身由 zxyz-common 的 MqWireContractTest 兜底。");
        }

        // 规则 2：陈旧豁免 —— 名单里的文件已不再违规 ⇒ 名单成了谎言，要求移除条目
        // ⚠️ 路径拼接必须走 Paths.get（CI 的 Linux 上 '\' 是合法文件名字符而非分隔符——
        //   2026-10-11 CI 首跑实证：".replace('/', '\\')" 让 5 条豁免全部「文件不存在」假红）
        List<String> staleExemptions = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : GRANDFATHERED_MOCK_MAPPER_TESTS.entrySet()) {
            for (String relativePath : entry.getValue()) {
                Path file = backendRoot.resolve(entry.getKey()).resolve(relativePath);
                if (!Files.isRegularFile(file)) {
                    staleExemptions.add(entry.getKey() + " → " + relativePath + "（文件不存在）");
                    continue;
                }
                List<Violation> found = detectMockObjectMapper(entry.getKey(), file, readText(file));
                if (found.isEmpty()) {
                    staleExemptions.add(entry.getKey() + " → " + relativePath
                            + "（已不再 mock ObjectMapper —— 请从 GRANDFATHERED_MOCK_MAPPER_TESTS 移除该条目）");
                }
            }
        }
        if (!staleExemptions.isEmpty()) {
            throw new AssertionError("【陈旧豁免】豁免名单里有条目已经不再成立：\n  - "
                    + String.join("\n  - ", staleExemptions)
                    + "\n豁免名单是「关于现状的谎言」比没有名单更糟：后来者会据信这些文件仍在 mock mapper。"
                    + "修复完成是好事 —— 请同步从 GRANDFATHERED_MOCK_MAPPER_TESTS 删除对应条目。");
        }
    }

    // ==========================================================================
    // 检测核心（纯函数形式，供 G1 自检直接喂样本）
    // ==========================================================================

    /**
     * 注释剥离（<b>等长置换</b>）：把注释内容替换为同长度空格。
     * <p>必须保长——违规行号要从剥离后的偏移反算回原文，长度变了行号会漂移。</p>
     */
    static String blankComments(String source) {
        String out = BLOCK_COMMENT.matcher(source).replaceAll(m -> " ".repeat(m.group().length()));
        return LINE_COMMENT.matcher(out).replaceAll(m -> " ".repeat(m.group().length()));
    }

    /** 字符串字面量（不含跨行文本块；本仓 mq 测试与门禁自检样本均用 \n 转义书写）。 */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");

    /**
     * 字符串字面量剥离（等长置换）：把字符串内容替换为同长度空格。
     * <p>为什么必须：本门禁的 G1 自检样本本身就以字符串字面量写在本测试源码里，
     * 文件级扫描若不剥离字符串，会把「门禁自己的样本」当成违规——自指死循环。
     * 真实违规（注解/代码形态）不在字符串内，不受影响。</p>
     */
    static String blankStringLiterals(String source) {
        return STRING_LITERAL.matcher(source).replaceAll(m -> " ".repeat(m.group().length()));
    }

    /** 文件级扫描的标准预处理：注释 → 字符串字面量，均等长置换（行号不漂移）。 */
    static String forFileScan(String source) {
        return blankStringLiterals(blankComments(source));
    }

    /**
     * 检测单个测试源文件里的 mock ObjectMapper 形态。
     * <p>两条通道：① ObjectMapper 声明前 {@link #MOCK_FIELD_ANNOTATIONS} 视窗内有注解
     * （允许 @Mock 与字段之间隔注释行）；② {@code mock(ObjectMapper.class)} 内联调用。
     * 已知合法形态不报：{@code @Spy} 真实例（audit OperateLogConsumerTest 形态）、
     * {@code new ObjectMapper()} 直接构造。</p>
     */
    static List<Violation> detectMockObjectMapper(String module, Path file, String source) {
        String stripped = forFileScan(source);
        List<Violation> violations = new ArrayList<>();

        Matcher inline = INLINE_MOCK_MAPPER.matcher(stripped);
        while (inline.find()) {
            violations.add(new Violation(module, file, "mock(ObjectMapper.class) 内联静态 mock",
                    lineOf(source, inline.start())));
        }

        Matcher declaration = OBJECT_MAPPER_DECLARATION.matcher(stripped);
        while (declaration.find()) {
            String mockingAnnotation = annotationImmediatelyAbove(stripped, declaration.start());
            if (mockingAnnotation != null) {
                violations.add(new Violation(module, file,
                        mockingAnnotation + " ObjectMapper（事件反序列化被整体 mock）",
                        lineOf(source, declaration.start())));
            }
        }
        return violations;
    }

    /**
     * 在声明位置向前回溯（不超过 240 字符，遇 {@code ;}、{@code {}、{@code }}、{@code @Test} 即停），
     * 返回直接修饰该声明的 mock 注解；无则 null。
     * <p>遇到 {@code @Spy} 直接返回 null（@Spy 包真实实例是合法形态，不再继续回溯）。</p>
     */
    static String annotationImmediatelyAbove(String source, int declarationStart) {
        int floor = Math.max(0, declarationStart - 240);
        for (int i = declarationStart - 1; i >= floor; i--) {
            char c = source.charAt(i);
            if (c == ';' || c == '{' || c == '}') {
                return null;
            }
            if (source.startsWith("@Test", i) || source.startsWith("@BeforeEach", i)) {
                return null;
            }
            for (String annotation : MOCK_FIELD_ANNOTATIONS) {
                if (source.startsWith(annotation, i)) {
                    char after = source.charAt(i + annotation.length());
                    if (!Character.isLetterOrDigit(after) && after != '_') {
                        return annotation;
                    }
                }
            }
            if (source.startsWith("@Spy", i)) {
                char after = source.charAt(i + "@Spy".length());
                if (!Character.isLetterOrDigit(after) && after != '_') {
                    return null;
                }
            }
        }
        return null;
    }

    /** mq 包判定：路径含 /mq/ 段，或包声明含 .mq 段（uno.acloud.file.mq / uno.acloud.im.infrastructure.mq）。 */
    static boolean isMqPackage(Path fileRelativeToModuleRoot, String source) {
        String path = fileRelativeToModuleRoot.toString().replace('\\', '/');
        if (path.contains("/mq/")) {
            return true;
        }
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        if (!matcher.find()) {
            return false;
        }
        for (String segment : matcher.group(1).split("\\.")) {
            if ("mq".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGrandfathered(Violation violation) {
        String relative = relativeToModuleRoot(backendRoot(), violation.file());
        Set<String> listed = GRANDFATHERED_MOCK_MAPPER_TESTS.getOrDefault(violation.module(), Set.of());
        return listed.contains(relative.replace('\\', '/'));
    }

    // ==========================================================================
    // G1 自检：证明检查「真的会失败」且合规样本不误报
    // ==========================================================================

    @Test
    void G1_门禁自检_违规样本必须失败_合规样本必须放行() {
        Path fake = Paths.get("FakeConsumerTest.java");

        // 样本 1：@Mock ObjectMapper ⇒ 必须命中
        String mockField = "class X {\n  @Mock\n  private ObjectMapper objectMapper;\n}";
        List<Violation> hit = detectMockObjectMapper("zxyz-fake", fake, mockField);
        assertTrue(hit.size() == 1 && hit.get(0).kind().contains("@Mock"),
                "@Mock ObjectMapper 样本必须命中，实际: " + hit);

        // 样本 2：@Mock 与字段之间隔注释行（team-service 存量形态）⇒ 必须命中
        String mockWithComment = "class X {\n  @Mock\n  // 生产环境注入的 mapper\n  private ObjectMapper objectMapper;\n}";
        hit = detectMockObjectMapper("zxyz-fake", fake, mockWithComment);
        assertTrue(hit.size() == 1, "@Mock + 注释行形态必须命中（视窗回溯），实际: " + hit);

        // 样本 3：@MockitoBean ObjectMapper ⇒ 必须命中
        String mockitoBean = "class X {\n  @MockitoBean\n  private ObjectMapper objectMapper;\n}";
        hit = detectMockObjectMapper("zxyz-fake", fake, mockitoBean);
        assertTrue(hit.size() == 1 && hit.get(0).kind().contains("@MockitoBean"),
                "@MockitoBean ObjectMapper 样本必须命中，实际: " + hit);

        // 样本 4：mock(ObjectMapper.class) 内联 ⇒ 必须命中
        String inlineMock = "ObjectMapper om = mock(ObjectMapper.class);";
        hit = detectMockObjectMapper("zxyz-fake", fake, inlineMock);
        assertTrue(hit.size() == 1, "mock(ObjectMapper.class) 样本必须命中，实际: " + hit);

        // 样本 5：@Spy 包真实实例（audit 存量合法形态）⇒ 必须放行
        String spyReal = "class X {\n  @Spy\n  private ObjectMapper objectMapper = new ObjectMapper()"
                + ".registerModule(new JavaTimeModule());\n}";
        assertTrue(detectMockObjectMapper("zxyz-fake", fake, spyReal).isEmpty(),
                "@Spy 真实 mapper 是合法形态，不得误报");

        // 样本 6：裸 new ObjectMapper() 字段 ⇒ 必须放行
        String plainNew = "class X {\n  private final ObjectMapper objectMapper = new ObjectMapper();\n}";
        assertTrue(detectMockObjectMapper("zxyz-fake", fake, plainNew).isEmpty(),
                "直接构造真实 mapper 不得误报");

        // 样本 7：方法参数里的 ObjectMapper（构造器注入签名）⇒ 不得误报
        String constructorParam = "class X {\n  X(ObjectMapper objectMapper, Other other) { }\n"
                + "  void run() { ObjectMapper local = new ObjectMapper(); }\n}";
        assertTrue(detectMockObjectMapper("zxyz-fake", fake, constructorParam).isEmpty(),
                "构造器参数/局部变量不得误报");

        // 样本 8：注释里的 @Mock ObjectMapper 字样 ⇒ 不得误报（注释先剥离）
        String commentedOut = "class X {\n  // @Mock\n  // private ObjectMapper objectMapper;\n"
                + "  private final ObjectMapper objectMapper = new ObjectMapper();\n}";
        assertTrue(detectMockObjectMapper("zxyz-fake", fake, commentedOut).isEmpty(),
                "注释内容不得当成代码检测");

        // 样本 9：mq 包判定 —— 路径段与包声明两条通道都要通
        assertTrue(isMqPackage(Paths.get("src/test/java/uno/acloud/file/mq/UserDeletedEventConsumerTest.java"),
                "package uno.acloud.file.mq;\nclass X {}"), "路径 /mq/ 段必须判为 mq 包");
        assertTrue(isMqPackage(Paths.get("src/test/java/uno/acloud/im/infrastructure/mq/XTest.java"),
                "package uno.acloud.im.infrastructure.mq;"), "包声明 .mq 段必须判为 mq 包");
        assertTrue(isMqPackage(Paths.get("src/test/java/uno/acloud/common/mq/XTest.java"),
                "package uno.acloud.common.mq;"), "common 的 mq 工具测试同样属于 mq 包（本门禁也对它生效）");
        assertFalse(isMqPackage(Paths.get("src/test/java/uno/acloud/file/service/XTest.java"),
                "package uno.acloud.file.service;"), "非 mq 包不得误判");

        // 样本 10：Jackson 3 轨（tools.jackson）同名类同样在范围内 —— 门禁轨道无关：
        // mock 掉任何一轨的 ObjectMapper 都等于「事件反序列化能力零验证」，
        // 与 51 号 P0 的失败形态（对载荷结构的任何变更静默不可见）完全同型。
        String jackson3 = "class X {\n  @Mock\n  private tools.jackson.databind.ObjectMapper mapper;\n}";
        hit = detectMockObjectMapper("zxyz-fake", fake, jackson3);
        assertTrue(hit.size() == 1 && hit.get(0).kind().contains("@Mock"),
                "mock Jackson 3 轨 mapper 同样是违规（轨道无关），实际: " + hit);

        // 样本 11：字符串字面量里的违规样态（= 本 G1 方法自己源码的形态）⇒ 文件级扫描必须放行。
        // 这是「门禁自指」回归钉：本文件以字符串字面量携带全部违规样本，
        // 若文件级扫描不剥离字符串，本门禁会永远红（自我命中）。
        String asLiteral = "String s = \"class X {\\n  @Mock\\n  private ObjectMapper objectMapper;\\n}\";";
        assertTrue(detectMockObjectMapper("zxyz-fake", fake, asLiteral).isEmpty(),
                "字符串字面量内的违规样态（门禁自检样本的载体）不得当成代码检测");
    }

    // ==========================================================================
    // 扫描与工具（读盘）
    // ==========================================================================

    private static List<Path> scanMqTestFiles(Path backendRoot) {
        List<Path> result = new ArrayList<>();
        for (String module : readModuleNames(backendRoot.resolve("pom.xml"))) {
            Path testRoot = backendRoot.resolve(module).resolve("src").resolve("test").resolve("java");
            if (!Files.isDirectory(testRoot)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(testRoot)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> isMqPackage(Paths.get(relativeToModuleRoot(backendRoot, p)), readText(p)))
                        .forEach(result::add);
            } catch (IOException e) {
                throw new UncheckedIOException("遍历 " + testRoot + " 失败", e);
            }
        }
        result.sort(Path::compareTo);
        return result;
    }

    private static String moduleOf(Path backendRoot, Path file) {
        return backendRoot.relativize(file.toAbsolutePath().normalize()).getName(0).toString();
    }

    private static String relativeToModuleRoot(Path backendRoot, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if ("src".equals(normalized.getName(i).toString())) {
                return normalized.subpath(i, normalized.getNameCount()).toString().replace('\\', '/');
            }
        }
        return normalized.toString();
    }

    private static List<String> readModuleNames(Path rootPom) {
        String xml = readText(rootPom);
        Matcher matcher = Pattern.compile("<module>\\s*([^<\\s]+)\\s*</module>").matcher(xml);
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1).trim());
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("在 " + rootPom + " 里没有解析到任何 <module>");
        }
        return names;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
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
            Path testClasses = Paths.get(MqConsumerTestDeserializationGuardTest.class.getProtectionDomain()
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

    private static Path backendRoot() {
        Path root = moduleDir().getParent();
        if (root == null || !Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new IllegalStateException("反推出的后端根目录里没有 pom.xml: " + root);
        }
        return root;
    }
}
