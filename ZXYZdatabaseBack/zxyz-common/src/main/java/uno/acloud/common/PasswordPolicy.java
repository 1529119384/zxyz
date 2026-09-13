package uno.acloud.common;

import java.util.regex.Pattern;

/**
 * 口令复杂度策略（审计 2.1.5 ②）—— 全仓唯一口径。
 *
 * <h2>为什么集中成常量</h2>
 * <p>改之前「密码最少几位」散在两个地方且<b>写法不同</b>：user-service 的两个 DTO 用
 * {@code @Size(min = 6)} 字面量，team-service 用 {@code app.team.min-password-length}
 * 配置项 —— 于是同一次「把最小长度提到 8」的变更必须记得改三处，漏一处就会出现
 * 「注册要求 8 位、团队建号只要 6 位」这种不一致。本类把长度与复杂度收敛成编译期常量，
 * 供注解与程序内校验共用。</p>
 *
 * <h2>为什么长度与复杂度分开表达</h2>
 * <p>长度不足与复杂度不足是<strong>两种不同的失败</strong>，提示也必须不同 ——
 * 否则用户只知道「密码不合法」，不知道是「太短」还是「缺数字」。所以长度用
 * {@link #MIN_LENGTH}/{@link #MAX_LENGTH}（配 {@code @Size}），复杂度用
 * {@link #COMPLEXITY_REGEX}（配 {@code @Pattern}），两者各自带文案。</p>
 *
 * <h2>注意</h2>
 * <p>本策略只约束<strong>新建与修改</strong>口令（注册、改密）。存量用户的旧口令不会被追溯
 * 校验 —— 登录路径不经过这里，属有意为之：把复杂度策略追溯应用到存量口令只会把用户
 * 锁在门外，而不会让任何旧口令变强。</p>
 */
public final class PasswordPolicy {

    /** 口令最小长度。注解参数必须是编译期常量，故用 {@code static final int}。 */
    public static final int MIN_LENGTH = 8;

    /** 口令最大长度：与既有 {@code @Size(max = 128)} 一致，避免超长输入拖垮哈希计算。 */
    public static final int MAX_LENGTH = 128;

    /** 长度不满足时的提示文案。 */
    public static final String SIZE_MESSAGE = "密码长度为 " + MIN_LENGTH + "-" + MAX_LENGTH + " 个字符";

    /** 复杂度不满足时的提示文案。 */
    public static final String COMPLEXITY_MESSAGE = "密码必须同时包含字母和数字";

    /**
     * 复杂度正则：至少一个字母 + 至少一个数字，且整串不含换行。
     *
     * <p>用前置断言（{@code (?=...)}) 而不是「分别 count 再比较」，是因为它能直接作为
     * {@code @Pattern} 的表达式复用，无需在 DTO 里写自定义校验器 ——
     * 少一个校验器就少一处「注解与程序内校验口径不一致」的可能。</p>
     *
     * <p>刻意<strong>不加</strong> {@code Pattern.DOTALL}：默认模式下 {@code .} 不匹配
     * <strong>行终止符</strong>（{@code \n} / {@code \r} / {@code \u0085} / {@code \u2028} /
     * {@code \u2029}），于是含换行的口令会被判为非法 —— 这类字符在 HTTP 表单编码、
     * 日志与请求头里都容易被截断，属于应当直接拒绝的输入。</p>
     *
     * <p><b>注意边界</b>：{@code .} <strong>不</strong>排除空格与制表符，所以「含空白」的
     * 口令是<strong>合法</strong>的（口令短语本就该能带空格）。别把这条规则当成
     * 「含任何空白即非法」来理解 —— 那会连带拒掉合法口令。</p>
     */
    public static final String COMPLEXITY_REGEX = "^(?=.*[A-Za-z])(?=.*[0-9]).+$";

    private static final Pattern COMPILED = Pattern.compile(COMPLEXITY_REGEX);

    private PasswordPolicy() {
    }

    /** 完整校验（长度 + 复杂度）；{@code null} 与空白视为不合法。 */
    public static boolean isValid(String password) {
        if (password == null || password.isBlank()) {
            return false;
        }
        int length = password.length();
        return length >= MIN_LENGTH && length <= MAX_LENGTH && COMPILED.matcher(password).matches();
    }

    /**
     * 只校验长度。
     *
     * <p>给「历史上只关心长度」的调用点（例如 team-service 创建团队用户）使用，
     * 使它们与注册/改密共享同一个最小长度，而不被迫一并接受复杂度要求 ——
     * 那会是超出本次审计范围的行为变更。</p>
     */
    public static boolean hasValidLength(String password) {
        if (password == null) {
            return false;
        }
        int length = password.length();
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
    }
}
