package uno.acloud.common.mq;

import uno.acloud.common.util.LogSanitizer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 死信 / 重试耗尽场景的诊断信息提取（审计 L7 修复配套）。
 *
 * <p><b>为什么需要这个类</b>：审计 L7 定的口径是「<strong>不加 mq_dead_letter 表，只保留 ERROR
 * 日志</strong>」，那就等价于要求「日志本身必须带够上下文」——服务名、队列、消息 id、
 * payload 摘要、死亡次数。这些提取逻辑原先在 6 个服务里各有一份（甚至有的压根没有），
 * 本类集中一处，供全局 {@link LoggingMessageRecoverer}（重试耗尽那一刻）与各服务的
 * DLQ 消费者（消息真正落进死信队列之后）共用，避免两处口径漂移。</p>
 *
 * <p><b>payload 为什么只记「摘要 + 有上限的预览」，而不是全文</b>：</p>
 * <ul>
 *   <li>全文会把用户可控内容原样写进日志：既带来 log injection 风险（伪造日志行），
 *       也会把日志文件灌爆。</li>
 *   <li>但既然不加死信表、也不做自动重放，<strong>日志就是唯一的现场记录</strong>：
 *       只给 hash 会让现场完全不可读，只给全文又有上面两个问题。
 *       故两者都给 —— {@code payloadSha256_12} 用于跨日志比对/去重，
 *       清洗后的前 {@value #PREVIEW_LIMIT} 个字符用于人眼判断。</li>
 *   <li>{@code payloadLength} 单独记：预览会被截断，长度才是「到底多大」的判据。</li>
 * </ul>
 */
public final class MqMessageDiagnostics {

    /** payload 预览保留的最大字符数（先经 {@link LogSanitizer} 清洗，其自身上限为 1024）。 */
    public static final int PREVIEW_LIMIT = 512;

    /** 摘要取 sha256 十六进制的前若干位：足够区分消息，又不至于冗长。 */
    private static final int DIGEST_HEX_LEN = 12;

    /** 摘要计算不可用时的占位符（正常情况下不会出现）。 */
    private static final String DIGEST_UNAVAILABLE = "n/a";

    /** 死信头缺失时的占位符。 */
    private static final String NO_DEATH_INFO = "none";

    private MqMessageDiagnostics() {
    }

    /** payload 字节数；null 视为 0。 */
    public static int lengthOf(byte[] body) {
        return body == null ? 0 : body.length;
    }

    /** payload 字符数；null 视为 0。 */
    public static int lengthOf(String payload) {
        return payload == null ? 0 : payload.length();
    }

    /** 把 payload 字节按 UTF-8 解码为字符串；null 视为空串。 */
    public static String toText(byte[] body) {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 清洗 + 截断后的 payload 预览。null → 空串。
     *
     * <p>先走 {@link LogSanitizer}（去 CRLF 与控制字符、截到 1024），再截到
     * {@link #PREVIEW_LIMIT}，两级都要有一是因为两者的上限不同。</p>
     */
    public static String preview(String payload) {
        String cleaned = LogSanitizer.sanitize(payload);
        if (cleaned.length() <= PREVIEW_LIMIT) {
            return cleaned;
        }
        return cleaned.substring(0, PREVIEW_LIMIT) + "…(preview-truncated)";
    }

    /** {@link #preview(String)} 的字节入口。 */
    public static String preview(byte[] body) {
        return preview(toText(body));
    }

    /**
     * payload 的 sha256 前 {@value #DIGEST_HEX_LEN} 位十六进制。空 payload 返回 "n/a"，
     * 算法不可用时返回 "n/a"。
     */
    public static String digest(byte[] body) {
        if (body == null || body.length == 0) {
            return DIGEST_UNAVAILABLE;
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder sb = new StringBuilder(DIGEST_HEX_LEN);
            for (byte b : hash) {
                if (sb.length() >= DIGEST_HEX_LEN) {
                    break;
                }
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                if (sb.length() >= DIGEST_HEX_LEN) {
                    break;
                }
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return DIGEST_UNAVAILABLE;
        }
    }

    /** {@link #digest(byte[])} 的字符串入口（按 UTF-8 编码）。 */
    public static String digest(String payload) {
        return digest(payload == null ? null : payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从死信相关头里提取一行可读描述。
     *
     * <p>RabbitMQ 把消息投进 DLX 时会写 {@code x-first-death-*}（首次死亡信息）与
     * {@code x-death}（每次死亡的累计列表）。消息尚未被死信过时这些头都不存在，
     * 此时返回 {@value #NO_DEATH_INFO} —— <strong>这是正常情况</strong>：
     * 全局 recoverer 是在「重试刚耗尽、消息还没被死信」的那一刻打日志的。</p>
     *
     * <p>本方法对头的类型与结构做完全防御性处理：任何一个头缺失或类型不符都只降级为
     * 占位符，绝不抛异常 —— 它被调用的位置（recoverer / DLQ 消费者）本身就是故障现场，
     * 诊断代码再抛异常只会把现场变得更难查。</p>
     */
    public static String describeDeath(Map<String, ?> headers) {
        if (headers == null || headers.isEmpty()) {
            return NO_DEATH_INFO;
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "firstQueue", headers.get("x-first-death-queue"));
        append(sb, "firstReason", headers.get("x-first-death-reason"));
        append(sb, "firstExchange", headers.get("x-first-death-exchange"));
        int autoDeathCount = -1;
        Object death = headers.get("x-death");
        if (death instanceof List<?> list && !list.isEmpty()) {
            int total = 0;
            Object last = list.get(list.size() - 1);
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    Object count = m.get("count");
                    if (count instanceof Number n) {
                        total += n.intValue();
                    }
                }
            }
            if (total > 0) {
                autoDeathCount = total;
            }
            if (last instanceof Map<?, ?> m) {
                append(sb, "lastDeathQueue", m.get("queue"));
                append(sb, "lastDeathReason", m.get("reason"));
            }
        }
        if (autoDeathCount >= 0) {
            append(sb, "deathCount", autoDeathCount);
        }
        return sb.length() == 0 ? NO_DEATH_INFO : sb.toString();
    }

    private static void append(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(key).append('=').append(LogSanitizer.sanitize(text));
    }
}
