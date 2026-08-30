package uno.acloud.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalAllowList 白名单矩阵 + 过渡回退的单元测试。
 * 注意：本仓库 surefire 未启用 @Nested 扫描（全仓唯一 @Nested 测试即因此跑 0 用例），
 * 故统一用扁平顶层 @Test 保证被正确发现执行。
 */
class InternalAllowListTest {

    // ==================== 过渡模式（空白名单 → 回退 legacy token） ====================

    @Test
    @DisplayName("过渡兼容：legacy token 匹配应通过")
    void legacyTokenMatch() {
        InternalAllowList allowList = new InternalAllowList(Map.of(), "shared-token");
        assertTrue(allowList.verify(null, "shared-token"));
    }

    @Test
    @DisplayName("过渡兼容：legacy token 不匹配应拒绝")
    void legacyTokenMismatch() {
        InternalAllowList allowList = new InternalAllowList(Map.of(), "shared-token");
        assertFalse(allowList.verify(null, "wrong"));
        assertFalse(allowList.verify(null, ""));
        assertFalse(allowList.verify(null, null));
    }

    @Test
    @DisplayName("过渡兼容：legacy token 未配置应拒绝")
    void noLegacyTokenRejected() {
        InternalAllowList allowList = new InternalAllowList(Map.of(), null);
        assertFalse(allowList.verify("zxyz-team-service", "anything"));
    }

    @Test
    @DisplayName("过渡兼容：空白名单视为未启用矩阵")
    void emptyNotMatrix() {
        InternalAllowList allowList = new InternalAllowList(Map.of(), "shared-token");
        assertFalse(allowList.isMatrixEnabled());
    }

    // ==================== 矩阵模式（allowed-sources 非空） ====================

    private static InternalAllowList allowListWith() {
        Map<String, String> allowed = new HashMap<>();
        allowed.put("zxyz-team-service", "team-key");
        allowed.put("zxyz-user-service", "user-key");
        return new InternalAllowList(allowed, "shared-token");
    }

    @Test
    @DisplayName("矩阵模式：来源在白名单且密钥匹配 → 通过")
    void allowedCallerMatch() {
        InternalAllowList allowList = allowListWith();
        assertTrue(allowList.verify("zxyz-team-service", "team-key"));
        assertTrue(allowList.verify("zxyz-user-service", "user-key"));
    }

    @Test
    @DisplayName("矩阵模式：来源在白名单但密钥不匹配 → 拒绝")
    void allowedCallerWrongKey() {
        InternalAllowList allowList = allowListWith();
        assertFalse(allowList.verify("zxyz-team-service", "wrong-key"));
        assertFalse(allowList.verify("zxyz-team-service", "user-key"));
    }

    @Test
    @DisplayName("矩阵模式：来源不在白名单 → 拒绝")
    void callerNotAllowed() {
        InternalAllowList allowList = allowListWith();
        assertFalse(allowList.verify("zxyz-admin-service", "admin-key"));
    }

    @Test
    @DisplayName("矩阵模式：缺少来源服务标识 → 拒绝")
    void missingCaller() {
        InternalAllowList allowList = allowListWith();
        assertFalse(allowList.verify(null, "team-key"));
        assertFalse(allowList.verify("", "team-key"));
    }

    @Test
    @DisplayName("矩阵模式：非空白名单视为已启用矩阵，不再走 legacy")
    void matrixEnabledIgnoresLegacy() {
        InternalAllowList allowList = allowListWith();
        assertTrue(allowList.isMatrixEnabled());
        // legacy token 不因矩阵存在而对未列出来源放行
        assertFalse(allowList.verify("zxyz-team-service", "shared-token"));
    }
}