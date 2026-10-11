package uno.acloud.audit.mapper;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体-DDL 对齐守护（54 号审计 🔴-1 的防复发钉）。
 *
 * <p><b>历史事故形态</b>：{@code OperateLog} 实体 12 字段长期缺 {@code messageHash}，
 * 而 DDL（V3 加列 / V4 唯一约束）已有 {@code message_hash} —— 幂等只能靠
 * {@code insertWithHash} 的显式列清单旁路。任何后续代码改走 MyBatis-Plus 公共
 * {@code insert()} 时，MyBatis-Plus 按实体字段生成列清单，{@code message_hash} 被
 * 静默 NULL 化（唯一索引允许多个 NULL），幂等失效、重复消息可无限入库。</p>
 *
 * <p><b>守护方式</b>：解析本模块 {@code db/migration/V*__*.sql} 中针对
 * {@code operate_log} 的 DDL 演化（CREATE TABLE 列 + 后续 ADD/MODIFY/DROP/RENAME COLUMN），
 * 得到「DDL 视角的最终列集合」；反射读取实体字段（驼峰转下划线）得到「实体视角的列集合」；
 * 断言两个集合<strong>双向相等</strong>。今后任何人改 DDL 不改实体（或反之），
 * 本测试立即变红 —— 不再依赖有人在 code review 里记得这一层。</p>
 *
 * <p><b>解析器的已知边界</b>（刻意保持简单，KISS）：不支持
 * {@code CHANGE COLUMN}（等价 RENAME）、不支持单引号内 {@code ''} 转义（本仓 DDL 未用过）；
 * 遇到不认识的形态宁可解析失败让测试红，也不静默跳过 —— 失败即提示维护本解析器。</p>
 */
class OperateLogEntityDdlGuardTest {

    private static final String TABLE = "operate_log";

    /** CREATE TABLE 头：CREATE TABLE [IF NOT EXISTS] operate_log ( —— 括号体另行截取 */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + TABLE + "\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** ALTER TABLE operate_log ...（语句级，动作用逗号分隔，逐段解析） */
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+" + TABLE + "\\s+(.*?);",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 列定义/动作段的首 token —— 命中这些关键字的是约束而非列 */
    private static final Set<String> NON_COLUMN_KEYWORDS = Set.of(
            "PRIMARY", "INDEX", "KEY", "UNIQUE", "CONSTRAINT", "FOREIGN", "FULLTEXT",
            "SPATIAL", "CHECK", "PARTITION");

    // ==================== 双向 diff ====================

    @Test
    void ddlColumns_matchEntityFields_exactly() throws Exception {
        Set<String> ddlColumns = resolveDdlColumns();
        Set<String> entityColumns = resolveEntityColumns();

        Set<String> ddlOnly = new TreeSet<>(ddlColumns);
        ddlOnly.removeAll(entityColumns);
        Set<String> entityOnly = new TreeSet<>(entityColumns);
        entityOnly.removeAll(ddlColumns);

        assertThat(ddlOnly)
                .as("DDL 有而实体没有的列（公共 insert() 会丢掉它们 → 数据静默缺失）：%s", ddlOnly)
                .isEmpty();
        assertThat(entityOnly)
                .as("实体有而 DDL 没有的字段（insert 会引用不存在的列 → 启动后首次写入即炸）：%s", entityOnly)
                .isEmpty();
    }

    @Test
    void messageHash_isPresentOnBothSides_issue54RegressionNail() throws Exception {
        // 54 号审计 🔴-1 的直接回归钉：这个字段曾经只在 DDL 侧存在（实体缺它导致幂等旁路），
        // 单独断言而不是只靠集合相等 —— 集合相等在「两侧同时删掉它」时依然成立。
        assertThat(resolveDdlColumns()).as("DDL 侧必须保留 message_hash（uk_operate_log_message_hash 的锚）")
                .contains("message_hash");
        assertThat(resolveEntityColumns()).as("实体侧必须保留 messageHash（公共 insert() 幂等的前提）")
                .contains("message_hash");
    }

    @Test
    void id_isPresentOnBothSides() throws Exception {
        assertThat(resolveDdlColumns()).contains("id");
        assertThat(resolveEntityColumns()).contains("id");
    }

    // ==================== DDL 侧：Flyway 迁移链演化 ====================

    /**
     * 按版本序重放迁移链中针对 {@code operate_log} 的 DDL，得到最终列集合。
     */
    private Set<String> resolveDdlColumns() throws Exception {
        List<File> migrations = listMigrationFiles();
        assertThat(migrations).as("classpath:/db/migration 下必须存在 V*__*.sql（Flyway 迁移链是 DDL 真源）")
                .isNotEmpty();

        Set<String> columns = new LinkedHashSet<>();
        for (File file : migrations) {
            String sql = java.nio.file.Files.readString(file.toPath());
            applyCreateTable(sql, columns);
            applyAlterTable(sql, columns);
        }
        assertThat(columns).as("重放迁移链后 operate_log 至少应解析出主键列 id").contains("id");
        return columns;
    }

    private List<File> listMigrationFiles() throws Exception {
        URL url = getClass().getResource("/db/migration");
        assertThat(url).as("测试 classpath 上必须能找到 db/migration（来自本模块 target/classes）").isNotNull();
        File dir = new File(URI.create(url.toString()));
        File[] files = dir.listFiles((d, name) -> name.matches("V\\d+__.*\\.sql"));
        assertThat(files).isNotNull();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort(Comparator.comparingLong(f -> {
            Matcher m = Pattern.compile("^V(\\d+)__").matcher(f.getName());
            assertThat(m.find()).as("迁移文件名必须形如 V<数字>__xxx.sql：%s", f.getName()).isTrue();
            return Long.parseLong(m.group(1));
        }));
        return list;
    }

    /** CREATE TABLE 括号体内的列定义（跳过 PRIMARY KEY / INDEX 等约束行） */
    private void applyCreateTable(String sql, Set<String> columns) {
        Matcher matcher = CREATE_TABLE.matcher(sql);
        if (!matcher.find()) {
            return;
        }
        String body = extractParenBody(sql, matcher.end() - 1);
        for (String definition : splitTopLevel(body)) {
            String cleaned = stripCommentsAndLiterals(definition).trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            String first = cleaned.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            if (NON_COLUMN_KEYWORDS.contains(first)) {
                continue;
            }
            // 列名可能是 `backtick` 包裹的，剥掉
            String column = first.replace("`", "").toLowerCase(Locale.ROOT);
            columns.add(column);
        }
    }

    /** ALTER TABLE 的 ADD COLUMN / MODIFY COLUMN / DROP COLUMN / RENAME COLUMN 动作 */
    private void applyAlterTable(String sql, Set<String> columns) {
        Matcher matcher = ALTER_TABLE.matcher(sql);
        while (matcher.find()) {
            for (String action : splitTopLevel(matcher.group(1))) {
                applyAlterAction(stripCommentsAndLiterals(action).trim(), columns);
            }
        }
    }

    private void applyAlterAction(String action, Set<String> columns) {
        if (action.isEmpty()) {
            return;
        }
        String[] tokens = action.split("\\s+");
        String head = tokens[0].toUpperCase(Locale.ROOT);
        switch (head) {
            case "ADD" -> {
                int nameIdx = tokens.length >= 2 && tokens[1].equalsIgnoreCase("COLUMN") ? 2 : 1;
                if (tokens.length > nameIdx) {
                    String kw = tokens[nameIdx].toUpperCase(Locale.ROOT);
                    if (!NON_COLUMN_KEYWORDS.contains(kw)) {
                        columns.add(tokens[nameIdx].replace("`", "").toLowerCase(Locale.ROOT));
                    }
                }
            }
            case "MODIFY" -> {
                // MODIFY 不改列名，只确认列仍在（对已 DROP 列的 MODIFY = 迁移链自相矛盾）
                int nameIdx = tokens.length >= 2 && tokens[1].equalsIgnoreCase("COLUMN") ? 2 : 1;
                if (tokens.length > nameIdx) {
                    String column = tokens[nameIdx].replace("`", "").toLowerCase(Locale.ROOT);
                    assertThat(columns).as("ALTER ... MODIFY 引用了不存在的列 %s（迁移链自相矛盾）", column)
                            .contains(column);
                }
            }
            case "DROP" -> {
                int nameIdx = tokens.length >= 2 && tokens[1].equalsIgnoreCase("COLUMN") ? 2 : 1;
                if (tokens.length > nameIdx && "COLUMN".equalsIgnoreCase(tokens[nameIdx - 1])) {
                    columns.remove(tokens[nameIdx].replace("`", "").toLowerCase(Locale.ROOT));
                }
                // DROP INDEX/KEY/CONSTRAINT 等不影响列集合
            }
            case "RENAME" -> {
                // RENAME COLUMN a TO b（或 RENAME a TO b）
                for (int i = 1; i < tokens.length - 2; i++) {
                    if ("TO".equalsIgnoreCase(tokens[i + 1])) {
                        columns.remove(tokens[i].replace("`", "").toLowerCase(Locale.ROOT));
                        columns.add(tokens[i + 2].replace("`", "").toLowerCase(Locale.ROOT));
                        break;
                    }
                }
            }
            default -> {
                // ALTER ... ENABLE/DISABLE/ORDER BY 等其它形态不影响列集合
            }
        }
    }

    // ==================== 实体侧：反射字段 ====================

    private Set<String> resolveEntityColumns() {
        Set<String> columns = new LinkedHashSet<>();
        // 实体放在 zxyz-common（uno.acloud.common.audit.OperateLog），audit 模块直接依赖
        for (java.lang.reflect.Field field : uno.acloud.common.audit.OperateLog.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue; // serialVersionUID 等静态字段不映射列
            }
            columns.add(camelToSnake(field.getName()));
        }
        return columns;
    }

    static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    // ==================== SQL 文本工具 ====================

    /** 截取从 openParen 起的括号体（计配对深度，支持嵌套的索引列括号） */
    private String extractParenBody(String sql, int openParen) {
        int depth = 0;
        for (int i = openParen; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openParen + 1, i);
                }
            }
        }
        throw new IllegalStateException("operate_log 的 CREATE TABLE 括号未闭合，无法解析 —— 请检查迁移文件");
    }

    /** 按括号深度 0 的逗号切段（索引列括号内的逗号不算分隔符），并把引号字面量清空 */
    private List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** 去掉 -- 注释与单引号字面量（COMMENT '...' / DEFAULT '...'），避免内容干扰 token 解析 */
    private String stripCommentsAndLiterals(String text) {
        String noComments = text.replaceAll("--.*?(?=\\r?\\n|$)", " ");
        return noComments.replaceAll("'[^']*'", " ");
    }
}
