package uno.acloud.common.audit;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 审计数据中敏感字段的打码器（发布端与消费端<b>共用同一份口径</b>）。
 *
 * <p><b>为什么必须共用</b>：审计链路此前只有发布端（{@link AbstractLogAspect}）脱敏，
 * 消费端无条件信任上游。一旦发布端漏脱敏，明文密码/token 就会<b>持久化</b>进
 * {@code operate_log}（区别于日志滚动，入库即长期留存）。口径提为公共实现后，
 * 消费端可原样复用再跑一次，形成第二道防线，两端口径也不会各自漂移。</p>
 *
 * <h2>三层防线（每层都有真实调用点）</h2>
 * <ol>
 *   <li><b>文本层 · JSON 形态</b>：{@link #mask(String)} 的 {@link #SENSITIVE_JSON_PATTERN}
 *       处理 Jackson 产出，如 {@code {"password":"s3cr3t"}}；值体可跨越转义引号
 *       （{@code s3\"cr3t}），单引号与无引号键也覆盖。</li>
 *   <li><b>文本层 · toString 形态</b>：{@link #SENSITIVE_TOSTRING_PATTERN} 处理
 *       {@code Arrays.toString} 的产出，如 {@code User(password=s3cr3t)}；
 *       分隔符覆盖 {@code =} / {@code :} / 空格，键名允许前后缀与 {@code _}/{@code -} 变体。</li>
 *   <li><b>结构层 · 对象反射</b>：{@link #maskArguments(Object[])} 由
 *       {@link AbstractLogAspect#recordLog} 调用。对参数<b>对象</b>扫描字段名
 *       （{@link #isSensitiveFieldName}），命中则把该字段值打码后重新渲染。
 *       这一层覆盖文本层<b>原理上无法覆盖</b>的情形：自定义 {@code toString} 只输出值不含键名、
 *       键名形态怪异等 —— 因为「哪个字段是敏感的」这一信息只有对象结构里才有，
 *       拼成文本后就丢失了。</li>
 * </ol>
 *
 * <h2>已知盲区（明确登记，避免误以为已被保护）</h2>
 * <ul>
 *   <li><b>非敏感键名 + 敏感值</b>：{@code User(name=s3cr3t)} 文本层无从判断（结构层可覆盖），
 *       但要在一段纯文本上恢复出「哪个值是口令」在原理上不可能。</li>
 *   <li><b>编码后的载荷</b>：如 Base64 {@code eyJwYXNzd29yZCI6IngifQ==}，文本与字段名两层都不可识别。</li>
 *   <li><b>文本层单调用时的字段分隔符截断</b>：直接调用 {@link #mask(String)} 处理一串
 *       <b>已经拼好的文本</b>、且值本身含 {@code ,}/{@code )}/{@code ]}/{@code }} 时，
 *       值体会在这些分隔符处截断，<b>截断后的剩余片段保留原文</b>
 *       —— 如 {@code mask("[D(password=Zq7,Xk9)]")} 产出 {@code password=***,Xk9}（残留 {@code Xk9}）。
 *       <p><b>为什么没有把值体放宽到「吃掉整串」</b>：这些分隔符正是
 *       {@link #SENSITIVE_TOSTRING_PATTERN} 用来界定字段边界的依据；放宽它会牵动该正则的核心语义，
 *       而这个取舍本身是刻意的 —— 见 {@code SENSITIVE_TOSTRING_PATTERN} 的注释
 *       「宁可多打码也不漏凭据，接受空格分隔场景被多打一点码」。本轮按
 *       「最小改动、不扩大爆炸半径」定案不修（2026-10-03 Lead 裁定）。</p>
 *       <p><b>可达性</b>：经 {@link #maskArguments(Object[])} 的<b>对象路径不可达</b> ——
 *       结构层先按字段名把值<b>整体</b>替换为 {@code ***}，之后文本层再无明文可截断
 *       （实测：同一含逗号口令，{@code maskArguments} 路径残留 = 无，纯 {@code mask()} 路径残留 = 有）。</p>
 *       <p><b>但消费端是纯文本层</b>：{@code OperateLogConsumer} 落库前对
 *       {@code methodParams}/{@code returnValue}/{@code beforeValue}/{@code afterValue}
 *       直接调用 {@link #mask(String)}（4 处），结构层在那里<b>不生效</b>。
 *       当前不可达仅因为「发布端已先做过结构层脱敏」；<b>一旦新增不经切面的写入方，
 *       本条即重新可达</b>。将来重新评估详见
 *       {@code ISSUE/review-2026-10-02/AUDIT-BACKEND-2026-10-03.md} §6.3。</p></li>
 * </ul>
 * 这三条是<b>设计边界</b>而非缺陷。①② 有配套用例（{@code knownBlindSpot_*}）显式断言当前行为，
 * 以免后人误以为它们已被覆盖；③ 按定案不补用例（加了会变成「断言旧行为」或「注定失败的用例」），
 * 故<b>仅以本注释作为登记位置</b>。
 */
public final class SensitiveDataMasker {

    /** 掩码占位符。 */
    public static final String MASK = "***";

    /** 对象渲染的最大递归深度（防止深/环状结构拖垮审计链路）。 */
    private static final int MAX_DEPTH = 3;

    /** 单个对象最多渲染的字段数（防止超宽 DTO 灌爆日志）。 */
    private static final int MAX_FIELDS = 32;

    /**
     * 敏感字段关键词（小写）。供结构层 {@link #isSensitiveFieldName(String)} 的子串匹配使用。
     * <p>含 {@code pass_word}/{@code pass-word} 等分隔符变体 —— 下划线/短横线写法在真实
     * DTO 与自定义 toString 里都出现过，是审核（R1）实测漏掉的一类。</p>
     */
    public static final List<String> SENSITIVE_KEYWORDS = List.of(
            "password", "pass_word", "pass-word", "passwd", "pwd",
            "token", "secret", "authorization", "credential", "apikey", "api_key", "api-key");

    /**
     * 正则里的关键词分支（长词在前，避免短词抢先匹配）。
     * <p>{@code _}/{@code -} 变体必须显式列出：{@link #KEY_PREFIX} 只负责匹配
     * 「关键词之前」的部分，关键词本体需逐字命中。</p>
     */
    private static final String KEY = "pass_word|pass-word|api_key|api-key|authorization"
            + "|credential|password|passwd|apikey|pwd|token|secret";

    /** 键名前后缀字符类：允许 {@code _} 与 {@code -}，覆盖 {@code pass_word}/{@code pass-word}。 */
    private static final String KEY_PREFIX = "[A-Za-z0-9_$-]*";

    /** 键名两侧可能存在的引号（JSON 用 {@code "}，非严格 JSON 用 {@code '}）。 */
    private static final String OPTIONAL_QUOTE = "['\"]?";

    /**
     * JSON 形态 · 双引号值：{@code "password":"s3cr3t"} → {@code "password":"***"}。
     *
     * <p><b>值体为什么是 {@code ((?:[^"\\]|\\.)*)} 而不是 {@code ([^"]*)}（R1 核心修复）</b>：
     * 旧写法遇到转义引号就停。口令含双引号时 Jackson <b>必然</b>把它转义成 {@code \"}，
     * 于是 {@code {"password":"s3\"cr3t"}} 只被打掉 {@code s3}，
     * 结果 {@code {"password":"***"cr3t"}} —— <b>既是明文残留，又产出非法 JSON</b>。
     * 新值体「非引号非反斜杠」或「反斜杠+任意字符（一个转义序列）」可跨越 {@code \"}。</p>
     *
     * <p>键名两侧引号可选，故同时覆盖 {@code {password:"x"}} 这类无引号键形态。</p>
     */
    public static final Pattern SENSITIVE_JSON_PATTERN = Pattern.compile(
            "(?i)(" + OPTIONAL_QUOTE + KEY_PREFIX + "(?:" + KEY + ")" + OPTIONAL_QUOTE + "\\s*:\\s*\")"
                    + "((?:[^\"\\\\]|\\\\.)*)"
                    + "(\")");

    /**
     * JSON 形态 · 单引号值：{@code {'password':'s3cr3t'}} → {@code {'password':'***'}}。
     * <p>单引号不是合法 JSON，但会出现在「手工拼接的字符串」「JS 风格日志」里，
     * 审核实测确有残留。键名两侧同样允许引号，否则 {@code 'password':} 这种形态匹配不到。</p>
     */
    private static final Pattern SENSITIVE_SINGLE_QUOTED_PATTERN = Pattern.compile(
            "(?i)(" + OPTIONAL_QUOTE + KEY_PREFIX + "(?:" + KEY + ")" + OPTIONAL_QUOTE + "\\s*:\\s*')"
                    + "((?:[^'\\\\]|\\\\.)*)"
                    + "(')");

    /**
     * Java toString 形态：{@code password=s3cr3t} → {@code password=***}。
     *
     * <p><b>分隔符为什么是 {@code [:]=} 或空白</b>：Lombok 用 {@code =}，
     * 但自定义 toString 常见 {@code password: xxx}（冒号）与 {@code password xxx}（纯空格），
     * 审核实测这两种都原样不脱敏。空白分支要求<b>至少一个</b>空白字符，
     * 这样 {@code passwordExpireDays=90} 这类「关键词只是前缀、并非敏感字段」的名字不会被误打码。</p>
     *
     * <p><b>值体为什么只排除 {@code , ) ] } }（不排除空格/引号）</b>：这些是 toString 的
     * <b>字段分隔符</b>，而值本身完全可能含空格或引号 —— {@code token="tok with spaces"}
     * 若按空格截断只会打掉 {@code "tok}，属于「看起来脱敏了、实际没脱干净」的假安全。
     * 代价是「空格分隔且无逗号」的自定义 toString 会被多打一点码，刻意接受：
     * 少一点可读性，换不漏明文凭据。</p>
     *
     * <p><b>键名允许前后缀</b>：{@code oldPassword=}、{@code new_password=}、
     * {@code reset-token=}、{@code accessToken=} 都会被命中 —— 带前后缀的命名恰恰是
     * 「精确键名」最容易漏掉的一类（Lombok {@code @ToString} 输出原字段名）。</p>
     *
     * <p>分组：1 = 键名，2 = 值体；替换为 {@code $1=***}（统一归一为 {@code =} 形态）。</p>
     */
    public static final Pattern SENSITIVE_TOSTRING_PATTERN = Pattern.compile(
            "(?i)(" + KEY_PREFIX + "(?:" + KEY + "))"
                    + "(?:\\s*[:=]\\s*|\\s+)"
                    + "([^,)\\]}]+)");

    /** {@link #hasSensitiveField(Class)} 的缓存：反射走查类层次不便宜，而参数类型是有限集合。 */
    private static final Map<Class<?>, Boolean> SENSITIVE_FIELD_CACHE = new ConcurrentHashMap<>();

    private SensitiveDataMasker() {
    }

    /**
     * 文本层打码：依次应用 JSON（双引号）、JSON（单引号）、toString 三种形态。
     *
     * <p>三种形态互不冲突（JSON 形态要求键后跟冒号+引号，toString 形态要求键后跟
     * {@code =}/{@code :}/空白），顺序执行即可覆盖；对不含敏感字段的文本是几次 O(n) 扫描。</p>
     *
     * @param value 原始文本（可空）
     * @return 打码后的文本；入参为 null/空串时原样返回
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = SENSITIVE_JSON_PATTERN.matcher(value).replaceAll("$1" + MASK + "$3");
        masked = SENSITIVE_SINGLE_QUOTED_PATTERN.matcher(masked).replaceAll("$1" + MASK + "$3");
        return SENSITIVE_TOSTRING_PATTERN.matcher(masked).replaceAll("$1=" + MASK);
    }

    /**
     * 结构层打码：把方法参数（<b>对象</b>）渲染为安全的审计字符串。
     *
     * <p>由 {@link AbstractLogAspect#recordLog} 调用 —— 这是 {@link #isSensitiveFieldName(String)}
     * 的真实生产调用点。对「含敏感字段名」的对象<b>逐字段</b>渲染：敏感字段打码、
     * 其余字段保留（保住审计价值，不是整对象丢弃）；不含敏感字段的对象走文本层，
     * 行为与改造前一致。</p>
     *
     * <p><b>为什么必须在对象层做</b>：像 {@code toString()} 只返回口令值而不含键名的类，
     * 拼成文本后「哪个值是口令」这个信息已经丢失，任何文本正则都不可能补回来。</p>
     *
     * @param args 方法参数数组（可空）
     * @return 形如 {@code [Dto(user=bob, password=***)]} 的安全文本；无参数时返回 {@code "[]"}
     */
    public static String maskArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            appendSafe(out, args[i], 0, visiting);
        }
        return out.append(']').toString();
    }

    /**
     * 字段名是否敏感（大小写不敏感，<b>子串</b>匹配）。
     *
     * <p>用子串而非「等于」：{@code oldPassword}/{@code new_password}/{@code reset-token}/
     * {@code accessToken} 这类带前后缀的命名同样属于敏感字段，而正则形态永远可能被新的
     * {@code toString} 风格绕过。</p>
     *
     * <p><b>调用方</b>：{@link #maskArguments(Object[])}（经 {@link #hasSensitiveField(Class)}
     * 缓存的类级判断）—— 这是本方法作为「第三层防线」的<b>实际执行路径</b>。</p>
     *
     * @param fieldName 字段名（可空）
     * @return 命中任一关键词时返回 true
     */
    public static boolean isSensitiveFieldName(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return false;
        }
        String lower = fieldName.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 结构层打码（JSON 树形态）：遍历 Jackson 树，把<b>键名敏感</b>的节点值替换为 {@link #MASK}。
     *
     * <p><b>为什么返回值走这条而不是 {@link #maskArguments}（保持 operate_log 契约）</b>：
     * {@code return_value} 一直是 Jackson 序列化后的 <b>JSON 文本</b>（如字符串返回值存为
     * {@code "ok"}）。若改用 {@code maskArguments} 渲染，形状会变成 {@code [ok]} ——
     * 既破坏既有列契约，也让按 JSON 解析该列的下游（导出/检索）失效。
     * 本方法在<b>保持 JSON 形状</b>的前提下做结构层打码。</p>
     *
     * <p>用键名子串匹配（{@link #isSensitiveFieldName}）而非正则，因此
     * {@code pass_word}/{@code api-key}/{@code oldPassword} 等变体同样命中。</p>
     *
     * @param node Jackson 树（可空）
     * @return 打码后的树（原地修改并返回同一实例；入参为 null 时返回 null）
     */
    public static com.fasterxml.jackson.databind.JsonNode maskJsonTree(
            com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            List<String> names = new java.util.ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isSensitiveFieldName(name)) {
                    object.put(name, MASK);
                } else {
                    maskJsonTree(object.get(name));
                }
            }
        } else if (node.isArray()) {
            node.forEach(SensitiveDataMasker::maskJsonTree);
        }
        return node;
    }

    // ------------------------------------------------------------------
    // 结构层实现
    // ------------------------------------------------------------------

    private static void appendSafe(StringBuilder out, Object value, int depth, Set<Object> visiting) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (isScalar(value) || depth >= MAX_DEPTH) {
            out.append(mask(String.valueOf(value)));
            return;
        }
        // 环状引用防护：同一条引用链上出现过的对象不再展开
        if (!visiting.add(value)) {
            out.append("<cyclic>");
            return;
        }
        try {
            Class<?> type = value.getClass();
            if (type.isArray()) {
                appendArray(out, value, depth, visiting);
            } else if (value instanceof Map<?, ?> map) {
                appendMap(out, map, depth, visiting);
            } else if (value instanceof Collection<?> collection) {
                appendCollection(out, collection, depth, visiting);
            } else if (hasSensitiveField(type)) {
                appendMaskedPojo(out, value, type, depth, visiting);
            } else {
                // 无敏感字段名 ⇒ 与改造前行为一致，只做文本层打码
                out.append(mask(String.valueOf(value)));
            }
        } catch (Exception e) {
            // 结构层任何异常都不得影响业务方法与审计发布：降级为文本层
            out.append(mask(String.valueOf(value)));
        } finally {
            visiting.remove(value);
        }
    }

    private static void appendArray(StringBuilder out, Object array, int depth, Set<Object> visiting) {
        int length = java.lang.reflect.Array.getLength(array);
        out.append('[');
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            appendSafe(out, java.lang.reflect.Array.get(array, i), depth + 1, visiting);
        }
        out.append(']');
    }

    private static void appendCollection(StringBuilder out, Collection<?> collection,
                                         int depth, Set<Object> visiting) {
        out.append('[');
        int i = 0;
        for (Object item : collection) {
            if (i++ > 0) {
                out.append(", ");
            }
            appendSafe(out, item, depth + 1, visiting);
        }
        out.append(']');
    }

    private static void appendMap(StringBuilder out, Map<?, ?> map, int depth, Set<Object> visiting) {
        out.append('{');
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i++ > 0) {
                out.append(", ");
            }
            // 键名参与敏感判断：{"password": "x"} 这类 Map 载荷同样要打码
            if (isSensitiveFieldName(String.valueOf(entry.getKey()))) {
                out.append(mask(String.valueOf(entry.getKey()))).append('=').append(MASK);
            } else {
                appendSafe(out, entry.getKey(), depth + 1, visiting);
                out.append('=');
                appendSafe(out, entry.getValue(), depth + 1, visiting);
            }
        }
        out.append('}');
    }

    /** 逐字段渲染：敏感字段打码，其余字段保留值（保住审计可读性）。 */
    private static void appendMaskedPojo(StringBuilder out, Object value, Class<?> type,
                                         int depth, Set<Object> visiting) {
        out.append(type.getSimpleName()).append('(');
        int rendered = 0;
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                if (field.isSynthetic() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (rendered > 0) {
                    out.append(", ");
                }
                out.append(field.getName()).append('=');
                if (isSensitiveFieldName(field.getName())) {
                    out.append(MASK);
                } else {
                    appendSafe(out, readField(field, value), depth + 1, visiting);
                }
                if (++rendered >= MAX_FIELDS) {
                    out.append(", ...");
                    return;
                }
            }
        }
        out.append(')');
    }

    /**
     * 读字段值。失败不抛出：JDK 类的字段可能因模块系统不可访问
     * （{@code InaccessibleObjectException}），此时降级为占位符 ——
     * 审计链路绝不能因为读不到某个字段而中断业务方法。
     */
    private static Object readField(java.lang.reflect.Field field, Object target) {
        try {
            if (!field.canAccess(target)) {
                field.setAccessible(true);
            }
            return field.get(target);
        } catch (Throwable t) {
            return "<unreadable>";
        }
    }

    /** 类（含父类）是否声明了任何字段名敏感的字段；结果缓存，避免热路径反复反射。 */
    private static boolean hasSensitiveField(Class<?> type) {
        return SENSITIVE_FIELD_CACHE.computeIfAbsent(type, key -> {
            for (Class<?> current = key; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                for (java.lang.reflect.Field field : current.getDeclaredFields()) {
                    if (field.isSynthetic()
                            || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (isSensitiveFieldName(field.getName())) {
                        return Boolean.TRUE;
                    }
                }
            }
            return Boolean.FALSE;
        });
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Class<?>
                || value instanceof java.time.temporal.Temporal
                || value instanceof java.util.Date
                || value instanceof java.util.UUID;
    }
}
