package uno.acloud.file.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uno.acloud.file.service.FileLifecyclePort;
import uno.acloud.file.service.FileOperationPort;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.service.FileUploadPort;
import uno.acloud.file.storage.StorageProviderRegistry;
import uno.acloud.file.storage.UploadInfo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F13（P3 可维护性/日志）：{@code getUploadSign} 必须在写日志前清洗文件名。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code FileController.getUploadSign} 在 {@code validateInputName} <b>之前</b>就把原始
 * {@code originalName} 写进日志（该校验发生在下游 {@code fileUploadPort.getUploadSign} 内部）。
 * 用户可控的 CRLF 因此能先落日志 —— 可以伪造出额外的「日志行」，掩盖真实事件
 * （典型的 log injection / 日志伪造）。仓库里已有 {@code LogSanitizer} 却未被使用。</p>
 *
 * <h2>本用例的判据</h2>
 * <p>真实捕获 logback 输出，断言：</p>
 * <ol>
 *   <li>日志里<b>不出现</b>原始换行（否则一条日志被拆成多行 = 伪造成功）；</li>
 *   <li>日志里出现清洗后的形态（证明是「清洗」而非「干脆不打日志」——后者会丢掉可观测性）。</li>
 * </ol>
 * <p>注意本用例<b>不</b>断言「非法文件名被拒绝」：那是下游校验的职责，这里只管日志安全。
 * 二者职责不重叠，测试也刻意分开。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileControllerLogSanitizationTest {

    @Mock
    private FileUploadPort fileUploadPort;
    @Mock
    private FileQueryPort fileQueryPort;
    @Mock
    private FileOperationPort fileOperationPort;
    @Mock
    private FileLifecyclePort fileLifecyclePort;
    @Mock
    private StorageProviderRegistry registry;

    private Logger controllerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        controllerLogger = (Logger) LoggerFactory.getLogger(FileController.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(appender);
        appender.stop();
    }

    private String capturedLog() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    /**
     * 核心断言：含 CRLF 的文件名必须被清洗后再落日志。
     * <p>若不修，日志里会出现字面换行 ⇒ 攻击者可凭空造出「另一条日志行」。</p>
     */
    @Test
    void getUploadSign_sanitizesCrlfInFileNameBeforeLogging() {
        String maliciousName = "ok.txt\r\n2026-10-03 ERROR 伪造的管理员操作日志";
        when(fileUploadPort.getUploadSign(eq(maliciousName), any(), eq(7L)))
                .thenReturn(new UploadInfo("oss", "https://oss.example.com/put", "files/key",
                        "https://oss.example.com/files/key", "text/plain", "attachment", 1L, true));

        FileController controller = new FileController(
                fileUploadPort, fileQueryPort, fileOperationPort, fileLifecyclePort, registry);
        controller.getUploadSign(7L, maliciousName, 10L);

        String log = capturedLog();
        assertTrue(log.contains("ok.txt"), "应保留可读部分；实际日志：" + log);
        assertFalse(log.contains("\r"), "日志中不得出现 CR（否则可伪造日志行）：" + log);
        assertFalse(log.contains("ok.txt\n2026-10-03"),
                "原始换行必须被替换为空格，不得把用户输入拆成新日志行：" + log);
        // 清洗后应拼成同一行（换行 → 空格）
        assertTrue(log.contains("ok.txt 2026-10-03 ERROR 伪造的管理员操作日志"),
                "清洗后仍应保留内容（只是压平为单行）—— 否则等于丢掉了可观测性： " + log);
    }

    /** 制表符外的控制字符必须被移除（与 LogSanitizer 的既有语义一致）。 */
    @Test
    void getUploadSign_removesControlCharactersFromLog() {
        String withNul = "evil\u0000.txt";
        when(fileUploadPort.getUploadSign(eq(withNul), any(), eq(7L)))
                .thenReturn(new UploadInfo("oss", "https://oss.example.com/put", "files/key",
                        "https://oss.example.com/files/key", "text/plain", "attachment", 1L, true));

        FileController controller = new FileController(
                fileUploadPort, fileQueryPort, fileOperationPort, fileLifecyclePort, registry);
        controller.getUploadSign(7L, withNul, 10L);

        String log = capturedLog();
        assertFalse(log.contains("\u0000"), "NUL 等控制字符必须被移除：" + log);
    }

    /**
     * 清洗不得改变「传给下游的原始值」—— 参数干净与否由下游 {@code validateInputName} 判定，
     * controller 只负责日志安全。若这里顺手把名字也改掉，就等于在控制器层静默篡改用户输入。
     */
    @Test
    void getUploadSign_stillPassesOriginalNameToService() {
        String maliciousName = "ok.txt\r\ninjected";
        when(fileUploadPort.getUploadSign(eq(maliciousName), any(), eq(7L)))
                .thenReturn(new UploadInfo("oss", "https://oss.example.com/put", "files/key",
                        "https://oss.example.com/files/key", "text/plain", "attachment", 1L, true));

        FileController controller = new FileController(
                fileUploadPort, fileQueryPort, fileOperationPort, fileLifecyclePort, registry);
        controller.getUploadSign(7L, maliciousName, 10L);

        // 下游必须拿到**未经清洗**的原值（校验与建名都依赖原始语义）
        verify(fileUploadPort).getUploadSign(maliciousName, 10L, 7L);
    }

    /** 正常文件名必须原样出现在日志里（清洗不应引入可见噪声）。 */
    @Test
    void getUploadSign_logsNormalFileNameUnchanged() {
        when(fileUploadPort.getUploadSign(eq("report.pdf"), any(), eq(7L)))
                .thenReturn(new UploadInfo("oss", "https://oss.example.com/put", "files/key",
                        "https://oss.example.com/files/key", "application/pdf", "attachment", 1L, true));

        FileController controller = new FileController(
                fileUploadPort, fileQueryPort, fileOperationPort, fileLifecyclePort, registry);
        controller.getUploadSign(7L, "report.pdf", 1024L);

        String log = capturedLog();
        assertTrue(log.contains("report.pdf"), "正常文件名应原样出现：" + log);
        assertTrue(log.contains("1024"), "声明大小也应出现在日志中：" + log);
    }
}
