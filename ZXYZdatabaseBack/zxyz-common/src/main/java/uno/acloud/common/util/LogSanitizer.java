package uno.acloud.common.util;

import java.util.regex.Pattern;

/**
 * 日志注入（log injection）防护工具。
 * <p>将写入日志的用户可控字段先清洗再输出：去除 CRLF（防止伪造日志行）、
 * 去除其它不可见控制字符，并截断到固定长度（防止灌爆日志）。</p>
 */
public final class LogSanitizer {

    /** 日志内容最大保留长度，超出截断并追加省略标记。 */
    private static final int MAX_LEN = 1024;

    /** 匹配除水平制表符 \t 之外的控制字符（码点 0x00–0x1F，但保留 0x09）。 */
    private static final Pattern CONTROL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");

    private LogSanitizer() {
    }

    /**
     * 清洗字符串：空值返回空串；CRLF/CR/LF 替换为空格；去除其它控制字符；
     * 截断到 {@link #MAX_LEN} 个字符，超出追加 "…(truncated)"。
     */
    public static String sanitize(String input) {
        if (input == null) {
            return "";
        }
        // 先把回车换行归一为空格，避免伪造日志行
        String normalized = input.replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ');
        // 去除其余不可见控制字符（保留水平制表符 \t）
        String cleaned = CONTROL_CHARS.matcher(normalized).replaceAll("");
        if (cleaned.length() > MAX_LEN) {
            return cleaned.substring(0, MAX_LEN) + "…(truncated)";
        }
        return cleaned;
    }

    /**
     * 清洗异常：空值返回空串，否则返回清洗后的异常 message。
     */
    public static String sanitize(Throwable t) {
        if (t == null) {
            return "";
        }
        return sanitize(t.getMessage());
    }
}
