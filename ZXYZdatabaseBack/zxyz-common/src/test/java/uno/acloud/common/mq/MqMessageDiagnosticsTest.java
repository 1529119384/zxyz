package uno.acloud.common.mq;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqMessageDiagnostics} 单元测试（审计 L7 修复配套）。
 *
 * <p>核心防回归点：本类处在「重试耗尽 / 已经死信」的故障现场，<strong>它自己绝不能再抛异常</strong>
 * —— 否则连日志都打不出来，等于把唯一的可观测信号也弄丢了。所以死信头里出现任何
 * 缺失、类型不符、结构异常，都必须降级为占位符而不是抛错。</p>
 */
class MqMessageDiagnosticsTest {

    // ==================== lengthOf ====================

    @Test
    void lengthOf_nullByteArray_isZero() {
        assertEquals(0, MqMessageDiagnostics.lengthOf((byte[]) null));
    }

    @Test
    void lengthOf_nullString_isZero() {
        assertEquals(0, MqMessageDiagnostics.lengthOf((String) null));
    }

    @Test
    void lengthOf_countsBytesAndChars() {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals(5, MqMessageDiagnostics.lengthOf(body));
        assertEquals(5, MqMessageDiagnostics.lengthOf("hello"));
    }

    // ==================== toText ====================

    @Test
    void toText_nullBody_isEmptyString() {
        assertEquals("", MqMessageDiagnostics.toText(null));
    }

    @Test
    void toText_decodesUtf8() {
        byte[] body = "中文".getBytes(StandardCharsets.UTF_8);
        assertEquals("中文", MqMessageDiagnostics.toText(body));
    }

    // ==================== preview ====================

    @Test
    void preview_null_isEmpty() {
        assertEquals("", MqMessageDiagnostics.preview((String) null));
        assertEquals("", MqMessageDiagnostics.preview((byte[]) null));
    }

    @Test
    void preview_stripsCrLfToDefeatLogInjection() {
        // 不处理的话，攻击者可以用换行伪造出一条「看起来像系统日志」的假行。
        assertEquals("a b c", MqMessageDiagnostics.preview("a\r\nb\nc"));
    }

    @Test
    void preview_truncatesAtPreviewLimitWithMarker() {
        String longPayload = "a".repeat(MqMessageDiagnostics.PREVIEW_LIMIT + 200);
        String preview = MqMessageDiagnostics.preview(longPayload);

        assertTrue(preview.endsWith("…(preview-truncated)"), "必须带截断标记，否则看日志的人会以为消息就这么长");
        assertEquals(MqMessageDiagnostics.PREVIEW_LIMIT + "…(preview-truncated)".length(), preview.length());
    }

    @Test
    void preview_keepsShortPayloadIntact() {
        assertEquals("{\"a\":1}", MqMessageDiagnostics.preview("{\"a\":1}"));
    }

    // ==================== digest ====================

    @Test
    void digest_matchesKnownSha256Prefix() {
        // sha256("hello") = 2cf24dba5fb0a30e...，取前 12 位。
        assertEquals("2cf24dba5fb0", MqMessageDiagnostics.digest("hello"));
        assertEquals("2cf24dba5fb0", MqMessageDiagnostics.digest("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void digest_isTwelveHexChars() {
        String digest = MqMessageDiagnostics.digest("{\"a\":1}");
        assertEquals("015abd7f5cc5", digest);
        assertEquals(12, digest.length());
        assertTrue(digest.matches("[0-9a-f]{12}"));
    }

    @Test
    void digest_emptyOrNullPayload_isPlaceholder() {
        assertEquals("n/a", MqMessageDiagnostics.digest((byte[]) null));
        assertEquals("n/a", MqMessageDiagnostics.digest(new byte[0]));
        assertEquals("n/a", MqMessageDiagnostics.digest((String) null));
        assertEquals("n/a", MqMessageDiagnostics.digest(""));
    }

    // ==================== describeDeath ====================

    @Test
    void describeDeath_nullOrEmptyHeaders_isNone() {
        assertEquals("none", MqMessageDiagnostics.describeDeath(null));
        assertEquals("none", MqMessageDiagnostics.describeDeath(new HashMap<>()));
    }

    @Test
    void describeDeath_reportsFirstDeathHeaders() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-first-death-queue", "zxyz.file.events");
        headers.put("x-first-death-reason", "rejected");
        headers.put("x-first-death-exchange", "zxyz.topic");

        String described = MqMessageDiagnostics.describeDeath(headers);

        assertTrue(described.contains("firstQueue=zxyz.file.events"), described);
        assertTrue(described.contains("firstReason=rejected"), described);
        assertTrue(described.contains("firstExchange=zxyz.topic"), described);
    }

    @Test
    void describeDeath_sumsXDeathCountsAndReportsLastEntry() {
        // RabbitMQ 的 x-death 是「每次死亡一条」的列表，count 需要累加才是总死亡次数。
        Map<String, Object> entry1 = new HashMap<>();
        entry1.put("count", 2L);
        entry1.put("queue", "zxyz.file.events");
        entry1.put("reason", "rejected");
        Map<String, Object> entry2 = new HashMap<>();
        entry2.put("count", 1L);
        entry2.put("queue", "zxyz.project.file-events");
        entry2.put("reason", "expired");

        Map<String, Object> headers = new HashMap<>();
        headers.put("x-death", List.of(entry1, entry2));

        String described = MqMessageDiagnostics.describeDeath(headers);

        assertTrue(described.contains("deathCount=3"), described);
        assertTrue(described.contains("lastDeathQueue=zxyz.project.file-events"), described);
        assertTrue(described.contains("lastDeathReason=expired"), described);
    }

    @Test
    void describeDeath_oddHeaderShapes_neverThrow() {
        // 各种"脏头"都必须被安静降级：诊断代码抛异常会把故障现场变得更难查。
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-first-death-queue", 123);            // 非字符串
        headers.put("x-first-death-reason", "");            // 空串视为缺失
        headers.put("x-death", "not-a-list");               // 类型完全不符
        assertEquals("firstQueue=123", MqMessageDiagnostics.describeDeath(headers));

        Map<String, Object> headers2 = new HashMap<>();
        headers2.put("x-death", List.of("not-a-map", 42));  // 列表元素不是 map
        assertEquals("none", MqMessageDiagnostics.describeDeath(headers2));

        Map<String, Object> headers3 = new HashMap<>();
        headers3.put("x-death", new ArrayList<>());          // 空列表
        assertEquals("none", MqMessageDiagnostics.describeDeath(headers3));

        Map<String, Object> headers4 = new HashMap<>();
        Map<String, Object> badEntry = new HashMap<>();
        badEntry.put("count", "3");                          // count 不是 Number
        headers4.put("x-death", List.of(badEntry));
        assertEquals("none", MqMessageDiagnostics.describeDeath(headers4));
    }

    @Test
    void describeDeath_sanitizesHeaderValues() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("x-first-death-queue", "evil\r\n2026-01-01 ERROR fake");

        String described = MqMessageDiagnostics.describeDeath(headers);

        assertFalse(described.contains("\n"), "头里的换行必须被清洗，否则可伪造日志行");
        assertFalse(described.contains("\r"), described);
    }
}
