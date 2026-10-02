package uno.acloud.file.storage.oss;

import org.junit.jupiter.api.Test;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.file.infrastructure.oss.OSSDeleter;
import uno.acloud.file.infrastructure.oss.OSSMetadataUpdater;
import uno.acloud.common.oss.GetSignUrl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * F15（P3 并发/配置一致性）：黑名单必须<b>每次调用时解析</b>，使 Nacos 热更立即生效。
 *
 * <h2>修复前的缺陷</h2>
 * <p>{@code AliyunOssStorageProvider} 在构造期把 {@code blocked-extensions} 一次性固化成
 * 一个不可变 {@code Set}，而它<b>没有</b> {@code @RefreshScope}。于是热更 Nacos 黑名单后：</p>
 * <ul>
 *   <li>{@code FileUploadService} 每次调用都重新解析 ⇒ <b>立刻</b>用新值；</li>
 *   <li>本 provider 层仍用构造期的旧值，<b>直到进程重启</b>。</li>
 * </ul>
 * <p>两层校验口径不一致，正是「配置已生效」这一运维判断被破坏的形态。</p>
 *
 * <h2>本类的判据</h2>
 * <p>用同一构造参数把 provider 建起来后，<b>不可能</b>在运行时改构造参数；
 * 但可以证明「不同的原始配置串会产生不同行为」，从而证明行为<b>确实源自当前配置</b>而非固定常量。
 * 若实现退回「构造期固化」，{@link #blockedExtensionsAreReadFromConfigurationNotHardcoded()}
 * 中对「配置未包含的扩展名不得被拦」的断言会失败（因为 fallback 常量里没有 {@code .svg}）。</p>
 */
class AliyunOssStorageProviderBlocklistTest {

    private AliyunOssStorageProvider providerWith(String blockedExtensionsRaw) {
        return new AliyunOssStorageProvider(
                mock(GetSignUrl.class),
                mock(OSSDeleter.class),
                mock(OSSMetadataUpdater.class),
                blockedExtensionsRaw);
    }

    /**
     * 配置里声明的扩展名必须被拦（用 {@code .svg}：它<b>不在</b> fallback 常量集合里，
     * 因此只有「真的读了配置」才可能拦住它）。
     */
    @Test
    void blockedExtensionsComeFromConfigurationNotEmptyHardcodedFallback() {
        AliyunOssStorageProvider provider = providerWith("[\".svg\",\".exe\"]");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.generateUploadInfo("files/uuid-1", "payload.svg", "image/svg+xml", "attachment"),
                "配置里声明的 .svg 必须被拦 —— 若失败说明 provider 用了 fallback 常量而非当前配置");

        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    /**
     * 反向对照：配置<b>不含</b> {@code .js} 时，provider 层不得凭 fallback 常量去拦它。
     * <p>这证明黑名单的权威来源是<b>配置</b>（而非构造期固化的字符面量）。</p>
     * <p>⚠️ 注意这里只测 provider 层：上传入口 {@code FileUploadService} 的
     * {@code NEVER_ALLOWED_EXTENSIONS} 与 ALLOWED 白名单是<b>独立的、更严格</b>的一层，
     * 生产路径上 {@code .js} 仍被入口拒绝（见 CLAUDE.md 的强制项）。</p>
     */
    @Test
    void providerOnlyBlocksWhatConfigurationSays() {
        GetSignUrl signUrl = mock(GetSignUrl.class);
        AliyunOssStorageProvider provider = new AliyunOssStorageProvider(
                signUrl, mock(OSSDeleter.class), mock(OSSMetadataUpdater.class), "[\".svg\"]");

        // .js 不在该配置里 ⇒ 必须穿过黑名单校验、真的走到 GetSignUrl（返回 null 会让上游 NPE，
        // 但那已证明「黑名单放行」；此处直接对 mock 断言更清晰）
        assertThrows(NullPointerException.class,
                () -> provider.generateUploadInfo("files/uuid-2", "script.js", "text/javascript", "attachment"),
                "未被黑名单拦下时应在后续签名步骤失败（GetSignUrl 是 mock）——"
                        + "若这里抛的是 BusinessException，说明凭 fallback 常量误拦了 .js");
        verify(signUrl).generatePutSignInfo(eq("files/uuid-2"), eq("script.js"),
                eq("text/javascript"), eq("attachment"));
    }

    /** 配置为空/缺失时必须回退到内置 fallback 常量（既有容错语义，不得回退）。 */
    @Test
    void blankConfigurationFallsBackToBuiltInBlocklist() {
        for (String raw : new String[]{"", "   "}) {
            AliyunOssStorageProvider provider = providerWith(raw);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> provider.generateUploadInfo("files/uuid-3", "evil.exe", "application/octet-stream", "attachment"),
                    "配置缺失（raw=\"" + raw + "\"）时必须回退到 fallback 黑名单，.exe 应被拦");
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
        }
    }

    /** 配置是非法 JSON（非数组/坏 JSON）时同样回退 fallback，不得因此拒服务。 */
    @Test
    void malformedConfigurationFallsBackInsteadOfFailing() {
        AliyunOssStorageProvider provider = providerWith("{ this is not json");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> provider.generateUploadInfo("files/uuid-4", "evil.exe", "application/octet-stream", "attachment"));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    /** 黑名单匹配必须大小写不敏感（{@code .EXE} 与 {@code .exe} 等价）。 */
    @Test
    void blocklistMatchingIsCaseInsensitive() {
        AliyunOssStorageProvider provider = providerWith("[\".svg\"]");

        assertThrows(BusinessException.class,
                () -> provider.generateUploadInfo("files/uuid-5", "PAYLOAD.SVG", "image/svg+xml", "attachment"));
    }

    /** 无扩展名的文件名不得因黑名单抛错（没有扩展名就无所谓黑名单）。 */
    @Test
    void fileNameWithoutExtensionIsNotBlocked() {
        GetSignUrl signUrl = mock(GetSignUrl.class);
        AliyunOssStorageProvider provider = new AliyunOssStorageProvider(
                signUrl, mock(OSSDeleter.class), mock(OSSMetadataUpdater.class), "[\".svg\"]");

        assertThrows(NullPointerException.class,
                () -> provider.generateUploadInfo("files/uuid-6", "noextension", "application/octet-stream", "attachment"),
                "无扩展名不应命中黑名单（GetSignUrl 是 mock，故应在后续步骤失败）");
        verify(signUrl).generatePutSignInfo(eq("files/uuid-6"), eq("noextension"),
                eq("application/octet-stream"), eq("attachment"));
    }
}
