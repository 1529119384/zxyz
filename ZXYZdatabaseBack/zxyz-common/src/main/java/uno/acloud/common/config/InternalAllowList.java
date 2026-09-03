package uno.acloud.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Map;

/**
 * 内部服务间鉴权的「允许来源 → 来源密钥」白名单矩阵。
 *
 * <p>通过 {@code @Value("#{...}")} 读取 {@code app.internal.allowed-sources}（扁平 map，
 * key=允许来源服务名，value=该来源独立密钥），与 common 内 {@code InternalServiceAuthInterceptor}
 * 采用相同 @Value 装配方式（common 的 @ConfigurationProperties 不会被各服务 @ConfigurationPropertiesScan
 * 扫到，故此处用 @Value）。</p>
 *
 * <p>校验：以请求 {@code X-Internal-Caller-Service} 为下标，在本地白名单找到该来源期望密钥，
 * 对 {@code X-Internal-Service-Token} 常量时间比对。目标服务只存被允许调用自己的来源的密钥，
 * 不存全部服务密钥——避免一个服务被攻破后伪造任意来源。</p>
 *
 * <p>过渡兼容：{@code allowed-sources} 为空时回退旧单一 {@code app.internal-service-token} 校验，
 * 保证 dev 本地与尚未迁移节点的平滑过渡。新旧密钥均禁止 YAML 默认值（CLAUDE.md 安全强制）。</p>
 */
@Component
// 重启生效（不可加 @RefreshScope）：内部服务鉴权白名单，刷新期间替换实例会打开鉴权空窗；
// 且 app.internal-service-token / app.internal.allowed-sources 属静态配置，不在 zxyz-dynamic.yml 热更清单内。
public class InternalAllowList {

    private static final Logger log = LoggerFactory.getLogger(InternalAllowList.class);

    private final Map<String, String> allowedSources;
    private final String legacyToken;

    public InternalAllowList(
            @Value("#{${app.internal.allowed-sources: {}}}") Map<String, String> allowedSources,
            @Value("${app.internal-service-token:}") String legacyToken) {
        this.allowedSources = allowedSources == null ? Collections.emptyMap() : allowedSources;
        this.legacyToken = legacyToken;
    }

    /**
     * 校验一次内部请求。
     *
     * @param callerService {@code X-Internal-Caller-Service} 来源服务名
     * @param providedToken  {@code X-Internal-Service-Token} 请求令牌
     * @return 校验通过返回 true
     */
    public boolean verify(String callerService, String providedToken) {
        // 过渡兼容：未配置矩阵 → 回退旧单一 token（常量时间比对）
        if (allowedSources.isEmpty()) {
            if (!hasText(legacyToken) || !hasText(providedToken)) {
                return false;
            }
            return constantTimeEquals(legacyToken, providedToken);
        }
        // 矩阵模式：caller 必须在白名单内
        if (!hasText(callerService)) {
            if (log.isWarnEnabled()) {
                log.warn("内部鉴权失败：缺少来源服务标识（X-Internal-Caller-Service）");
            }
            return false;
        }
        String expectedKey = allowedSources.get(callerService);
        if (!hasText(expectedKey) || !hasText(providedToken)) {
            if (log.isDebugEnabled()) {
                log.debug("内部鉴权失败：来源 {} 不在白名单或其密钥未配置", callerService);
            }
            return false;
        }
        if (!constantTimeEquals(expectedKey, providedToken)) {
            if (log.isDebugEnabled()) {
                log.debug("内部鉴权失败：来源 {} 密钥不匹配", callerService);
            }
            return false;
        }
        return true;
    }

    /** 是否已启用白名单矩阵（非空白名单） */
    public boolean isMatrixEnabled() {
        return allowedSources != null && !allowedSources.isEmpty();
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}