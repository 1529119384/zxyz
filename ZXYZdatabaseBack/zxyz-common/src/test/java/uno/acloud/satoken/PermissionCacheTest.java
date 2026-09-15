package uno.acloud.satoken;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PermissionCache} 行为测试。
 * <p>
 * 该类此前<b>完全没有测试</b>，而它的失效链路（Redis Pub/Sub）实际是断的
 * （没有发布方 + 监听器只在 gateway 注册），长期未被发现。这里把缓存语义与两种失效
 * 粒度都固定下来。
 */
class PermissionCacheTest {

    private PermissionCache cache;

    @BeforeEach
    void setUp() {
        cache = new PermissionCache();
    }

    @Test
    void 未命中返回null_写入后可读到() {
        assertNull(cache.getPermissions(1L, "login"));
        assertNull(cache.getRoles(1L, "login"));

        cache.putPermissions(1L, "login", List.of("a", "b"));
        cache.putRoles(1L, "login", List.of("admin"));

        assertEquals(List.of("a", "b"), cache.getPermissions(1L, "login"));
        assertEquals(List.of("admin"), cache.getRoles(1L, "login"));
    }

    @Test
    void 不同loginType互不干扰() {
        cache.putPermissions(1L, "web", List.of("a"));
        cache.putPermissions(1L, "app", List.of("b"));

        assertEquals(List.of("a"), cache.getPermissions(1L, "web"));
        assertEquals(List.of("b"), cache.getPermissions(1L, "app"));
    }

    @Test
    void 写入null不覆盖() {
        cache.putPermissions(1L, "login", List.of("a"));
        cache.putPermissions(1L, "login", null);

        assertEquals(List.of("a"), cache.getPermissions(1L, "login"));
    }

    @Test
    void 失效单个用户会清掉他所有loginType的权限与角色() {
        cache.putPermissions(1L, "web", List.of("a"));
        cache.putPermissions(1L, "app", List.of("b"));
        cache.putRoles(1L, "web", List.of("admin"));
        cache.putPermissions(2L, "web", List.of("c"));

        cache.invalidate(1L);

        assertNull(cache.getPermissions(1L, "web"));
        assertNull(cache.getPermissions(1L, "app"));
        assertNull(cache.getRoles(1L, "web"));
        assertEquals(List.of("c"), cache.getPermissions(2L, "web"), "其他用户不应受影响");
    }

    @Test
    void 全量失效会清空所有用户() {
        cache.putPermissions(1L, "web", List.of("a"));
        cache.putRoles(1L, "web", List.of("admin"));
        cache.putPermissions(2L, "app", List.of("b"));
        cache.putRoles(2L, "app", List.of("member"));

        cache.invalidateAll();

        assertNull(cache.getPermissions(1L, "web"));
        assertNull(cache.getRoles(1L, "web"));
        assertNull(cache.getPermissions(2L, "app"));
        assertNull(cache.getRoles(2L, "app"));
    }

    @Test
    void 失效不存在的用户是安全的() {
        cache.putPermissions(1L, "web", List.of("a"));
        cache.invalidate(999L);
        assertEquals(List.of("a"), cache.getPermissions(1L, "web"));
    }

    @Test
    void 消息协议常量约定() {
        // 这两个常量是「发布方—订阅方」之间的协议，改名会让两侧静默失配
        assertEquals("zxyz:permission:changed", PermissionCache.INVALIDATION_TOPIC);
        assertEquals("*", PermissionCache.INVALIDATE_ALL);
    }
}
