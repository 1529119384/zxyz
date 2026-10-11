package uno.acloud.file.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护不变量（2026-10-11 54 号 🟡）：关键词搜索的排序必须有索引支撑。
 *
 * <p>searchByKeyword/countByKeyword 的 SQL 形态是
 * 「个人：team_id IS NULL AND upload_user_id = ? ｜ 团队：team_id = ?」+
 * {@code ORDER BY modify_time DESC LIMIT/OFFSET}。若没有任何一条索引同时覆盖
 * 过滤列与 modify_time，排序阶段就会 filesort，深分页代价陡增。</p>
 *
 * <p>本测试按迁移版本顺序解析 {@code db/migration/V*.sql} 里的 INDEX 定义，
 * 断言以下两条复合索引在位（fail-closed：迁移文件缺失/正则全不命中即失败）：
 * <ul>
 *   <li>{@code idx_fn_user_modify (upload_user_id, team_id, modify_time)} —— 个人分支；</li>
 *   <li>{@code idx_fn_team_modify (team_id, modify_time)} —— 团队分支。</li>
 * </ul>
 * 与 {@code ImMessageContentColumnWidthTest} 同款口径：读源码树而非 classpath
 * （构建产物可能残留已删除迁移，造成幽灵读取）。
 */
class FileSearchOrderingIndexTest {

    private static final String USER_INDEX = "idx_fn_user_modify";
    private static final String TEAM_INDEX = "idx_fn_team_modify";

    /** 匹配 `INDEX idx_xxx (col1, col2, ...)`（V1 内联式）与 `ADD INDEX idx_xxx (...)`（增量式）。 */
    private static final Pattern INDEX_DEF = Pattern.compile(
            "(?:INDEX|KEY)\\s+`?(\\w+)`?\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.+\\.sql$");

    @Test
    void 搜索排序必须具备过滤加排序复合索引() throws IOException {
        List<Path> migrations = listMigrationsInVersionOrder();
        assertTrue(migrations.size() >= 8,
                "迁移文件数应 >= 8（V8 起才有搜索排序索引）——若你删了迁移文件，请同步评估本守护");

        // 按版本顺序累积解析（当前无同名重建场景，取最后一个命中的定义）
        String userCols = null;
        String teamCols = null;
        int userHits = 0;
        int teamHits = 0;
        for (Path sql : migrations) {
            String content = Files.readString(sql, StandardCharsets.UTF_8);
            Matcher m = INDEX_DEF.matcher(content);
            while (m.find()) {
                String name = m.group(1);
                String cols = m.group(2).replaceAll("`", "").replaceAll("\\s", "");
                if (USER_INDEX.equals(name)) { userCols = cols; userHits++; }
                if (TEAM_INDEX.equals(name)) { teamCols = cols; teamHits++; }
            }
        }

        assertTrue(userHits >= 1, "缺少 " + USER_INDEX + " 索引定义（个人空间搜索排序防线）");
        assertTrue(teamHits >= 1, "缺少 " + TEAM_INDEX + " 索引定义（团队空间搜索排序防线）");
        assertTrue(userCols != null && teamCols != null, "索引定义解析异常（fail-closed）");

        assertTrue(userCols.toLowerCase().startsWith("upload_user_id,team_id,modify_time"),
                USER_INDEX + " 列序必须为 (upload_user_id, team_id, modify_time)，实测: " + userCols
                        + " —— 列序错误会让个人分支的等值过滤+排序走不上该索引");
        assertTrue(teamCols.toLowerCase().startsWith("team_id,modify_time"),
                TEAM_INDEX + " 列序必须为 (team_id, modify_time)，实测: " + teamCols);
    }

    private List<Path> listMigrationsInVersionOrder() throws IOException {
        Path migrationDir = locateMigrationDir();
        try (Stream<Path> files = Files.list(migrationDir)) {
            List<Path> list = new ArrayList<>(files.filter(p -> MIGRATION_FILE.matcher(p.getFileName().toString()).matches()).toList());
            list.sort(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().substring(1, p.getFileName().toString().indexOf('_')))));
            return list;
        }
    }

    /** 沿父目录向上找 {@code src/main/resources/db/migration}（源码树，非 classpath）。 */
    private Path locateMigrationDir() {
        for (Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/resources/db/migration");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 src/main/resources/db/migration（fail-closed：找不到即失败，不静默跳过）");
    }
}
