package uno.acloud.im.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护不变量：{@code im_message.content_extracted} 的列宽 >= {@code app.im.message.max-text-length}。
 *
 * <p>为什么需要这个测试：本仓库出现过一次「代码常量与 DDL 宽度脱节」的事故 ——
 * V3 建的 {@code content_text VARCHAR(5000)} 与上限一致，V4 另建了
 * {@code content_extracted VARCHAR(2000)}，V5 收缩时删掉了前者、留下后者，
 * 于是列宽比应用允许的消息长度少 3000 个字符；2001..5000 字符的 TEXT 消息
 * 在严格模式下报 ERROR 1406、非严格模式下被静默截断（搜索里永久查不到）。
 *
 * <p>本测试把「两个数字」钉在一起，任一侧被改动都会立刻变红：
 * <ul>
 *   <li>左值：按迁移版本顺序解析出的最终列宽（后面的迁移覆盖前面的）；</li>
 *   <li>右值：{@code nacos-config/zxyz-dynamic.yml} 里运行时生效的
 *       {@code app.im.message.max-text-length}。</li>
 * </ul>
 *
 * <p>两侧都采用 <b>fail-closed</b>：探针找不到源文件时直接断言失败，而不是静默跳过 ——
 * 一个「找不到就跳过」的守卫等价于没有守卫。
 *
 * <p><b>两侧都刻意读「源码树」而不是 classpath。</b>早期版本用
 * {@code getClassLoader().getResource("db/migration")} 定位迁移目录，它会解析到
 * {@code target/classes/db/migration}（构建产物）。构建产物里可能残留已被删除的迁移
 * （Maven 的 resource 复制阶段只增不删），从而让守卫读到「幽灵文件」：
 * 既可能误报，也可能掩盖真实回归。源码树才是最终会被打包进产物的那一份，
 * 故此处与 {@link #locateNacosConfig()} 统一为「向上寻找源码树」。
 * <b>请勿改回 classpath 解析。</b>
 */
class ImMessageContentColumnWidthTest {

    private static final String COLUMN_NAME = "content_extracted";

    /** 同时匹配 `ADD COLUMN content_extracted VARCHAR(2000)` 与 `MODIFY COLUMN content_extracted VARCHAR(5000)`。 */
    private static final Pattern COLUMN_WIDTH = Pattern.compile(
            "`?" + COLUMN_NAME + "`?\\s+VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private static final String NACOS_CONFIG_RELATIVE = "nacos-config/zxyz-dynamic.yml";
    private static final String MAX_TEXT_LENGTH_PATH = "app.im.message.max-text-length";
    private static final int MAX_PARENT_HOPS = 6;

    /** 本测试所属模块名，用于在「从仓库根目录运行」时定位到本模块的源码树。 */
    private static final String MODULE_NAME = "zxyz-im-service";
    private static final String MIGRATION_RELATIVE = "src/main/resources/db/migration";

    @Test
    void generatedColumnMustAcceptEveryMessageTheApplicationAllows() {
        int columnWidth = effectiveColumnWidth();
        int configuredMax = configuredMaxTextLength();

        System.out.println("[column-width-guard] 列宽 = " + columnWidth + "，配置上限 = " + configuredMax);

        assertTrue(columnWidth >= configuredMax,
                "im_message." + COLUMN_NAME + " 列宽 (" + columnWidth + ") 小于应用上限 "
                        + MAX_TEXT_LENGTH_PATH + " (" + configuredMax + ")。"
                        + "这会让 " + (columnWidth + 1) + ".." + configuredMax + " 字符的 TEXT 消息"
                        + "在写入时报 ERROR 1406（严格模式）或被静默截断（非严格模式）。"
                        + "修法：新增一个 Flyway 迁移，把该生成列加宽到 >= " + configuredMax + " 个字符"
                        + "（参考 V6__widen_content_extracted_to_5000.sql 的 MODIFY COLUMN 写法）。");
    }

    /**
     * 按迁移版本顺序取「最后一次」出现该列的宽度 —— 后面的迁移语义上覆盖前面的。
     * 若某次迁移删除了该列（DROP COLUMN），仍然保留删除前的宽度：本测试关心的是
     * 「是否曾存在一个装不下应用上限的列」，删除属于另行处理。
     */
    private int effectiveColumnWidth() {
        Path directory = locateMigrationDirectory();
        List<Migration> migrations = readMigrationsInVersionOrder(directory);
        assertTrue(!migrations.isEmpty(),
                directory + " 下未找到任何迁移文件，无法校验列宽不变量");

        Integer width = null;
        String source = null;
        for (Migration migration : migrations) {
            Matcher matcher = COLUMN_WIDTH.matcher(migration.sql());
            while (matcher.find()) {
                width = Integer.valueOf(matcher.group(1));
                source = migration.fileName();
            }
        }
        assertTrue(width != null,
                "所有迁移文件中都没有出现 " + COLUMN_NAME + " 的 VARCHAR 宽度定义。"
                        + "若该列已被改名或删除，请同步更新 " + getClass().getSimpleName() + "。");

        System.out.println("[column-width-guard] 列宽取自 " + source);
        return width;
    }

    private List<Migration> readMigrationsInVersionOrder(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<Migration> migrations = new ArrayList<>();
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                Matcher matcher = MIGRATION_FILE.matcher(fileName);
                if (!matcher.matches()) {
                    continue;
                }
                migrations.add(new Migration(
                        Integer.parseInt(matcher.group(1)),
                        fileName,
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
            migrations.sort(Comparator.comparingInt(Migration::version));
            return migrations;
        } catch (IOException e) {
            throw new UncheckedIOException("读取迁移目录失败: " + directory, e);
        }
    }

    /**
     * 定位「源码树」里的迁移目录（<b>不是</b> classpath / {@code target/}），
     * 以覆盖两种真实执行形态：
     * <ol>
     *   <li>Surefire 在模块目录下执行（默认 {@code basedir}）→ 直接 {@code ./src/main/resources/db/migration}；</li>
     *   <li>工作目录被设为仓库根（如 {@code ZXYZdatabaseBack/} 或仓库顶层）→ 逐级向上找本模块目录。</li>
     * </ol>
     * 都找不到时 fail-closed 报错，绝不返回 null 或静默跳过。
     */
    private Path locateMigrationDirectory() {
        Path start = Path.of("").toAbsolutePath();

        Path direct = start.resolve(MIGRATION_RELATIVE);
        if (Files.isDirectory(direct)) {
            return direct;
        }

        for (Path current = start; current != null; current = current.getParent()) {
            Path inModule = current.resolve(MODULE_NAME).resolve(MIGRATION_RELATIVE);
            if (Files.isDirectory(inModule)) {
                return inModule;
            }
        }

        throw new AssertionError("从 " + start + " 起未能定位 " + MIGRATION_RELATIVE
                + "（既不在当前目录，也不在任意父目录下的 " + MODULE_NAME + "/ 内）。"
                + "列宽不变量依赖该目录，请修正搜索路径 —— 不要改回 classpath 解析，"
                + "classpath 上的 target/classes 可能残留已删除的迁移文件。");
    }

    /** 从当前工作目录逐级向上找仓库内的 nacos 动态配置，并解析出 IM 消息文本长度上限。 */
    private int configuredMaxTextLength() {
        Path configFile = locateNacosConfig();
        List<String> lines;
        try {
            lines = Files.readAllLines(configFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 nacos 配置失败: " + configFile, e);
        }

        // 极简缩进感知扫描：只认「空格缩进 + key: value」的子集，足够覆盖本仓库的
        // zxyz-dynamic.yml 形态，且不引入 YAML 解析依赖（避免为一条守卫加库）。
        Deque<Section> stack = new ArrayDeque<>();
        for (String rawLine : lines) {
            String line = stripComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            int indent = indentOf(line);
            String trimmed = line.trim();
            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                stack.pop();
            }

            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = unquote(trimmed.substring(0, colon).trim());
            String value = unquote(trimmed.substring(colon + 1).trim());

            if (value.isEmpty()) {
                stack.push(new Section(indent, key));
                continue;
            }

            if (MAX_TEXT_LENGTH_PATH.equals(dottedPath(stack, key))) {
                System.out.println("[column-width-guard] 配置取自 " + configFile);
                return Integer.parseInt(value);
            }
        }
        throw new AssertionError("在 " + configFile + " 中找不到 " + MAX_TEXT_LENGTH_PATH
                + "。该键是列宽不变量的另一半，缺失时无法校验 —— 请修正本测试的探针或恢复该键。");
    }

    private Path locateNacosConfig() {
        Path current = Path.of("").toAbsolutePath();
        for (int hop = 0; hop <= MAX_PARENT_HOPS && current != null; hop++) {
            Path candidate = current.resolve(NACOS_CONFIG_RELATIVE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("从 " + Path.of("").toAbsolutePath() + " 向上 " + MAX_PARENT_HOPS
                + " 层未找到 " + NACOS_CONFIG_RELATIVE + "。列宽不变量依赖该文件，请修正搜索路径。");
    }

    private static String dottedPath(Deque<Section> stack, String leafKey) {
        List<Section> ordered = new ArrayList<>(stack);
        Collections.reverse(ordered); // Deque 是后进先出，反转即得到由外到内的层级
        StringBuilder path = new StringBuilder();
        for (Section section : ordered) {
            path.append(section.name()).append('.');
        }
        return path.append(leafKey).toString();
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static int indentOf(String line) {
        int index = 0;
        while (index < line.length() && line.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static String unquote(String value) {
        return value.replace("'", "").replace("\"", "");
    }

    private record Migration(int version, String fileName, String sql) {
    }

    private record Section(int indent, String name) {
    }
}
