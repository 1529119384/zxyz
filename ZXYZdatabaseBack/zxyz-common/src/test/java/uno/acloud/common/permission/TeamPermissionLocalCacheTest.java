package uno.acloud.common.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TeamPermissionLocalCache} 行为测试。
 * <p>重点覆盖「失效」语义：权限缓存的正确性完全依赖失效是否精确，
 * 漏失效 = 权限回收不及时（安全问题），误失效 = 多一次远程调用（仅性能问题）。</p>
 */
class TeamPermissionLocalCacheTest {

    private TeamPermissionLocalCache cache;

    @BeforeEach
    void setUp() {
        cache = new TeamPermissionLocalCache();
    }

    @Test
    void 未命中返回null_命中返回缓存值() {
        assertNull(cache.getIfPresent(1L, 2L, "team:member:view"));

        cache.put(1L, 2L, "team:member:view", true);
        assertEquals(Boolean.TRUE, cache.getIfPresent(1L, 2L, "team:member:view"));

        cache.put(1L, 2L, "team:member:remove", false);
        assertEquals(Boolean.FALSE, cache.getIfPresent(1L, 2L, "team:member:remove"));
    }

    @Test
    void 不同code互不干扰() {
        cache.put(1L, 2L, "a", true);
        cache.put(1L, 2L, "b", false);

        assertTrue(cache.getIfPresent(1L, 2L, "a"));
        assertFalse(cache.getIfPresent(1L, 2L, "b"));
    }

    @Test
    void 相同code下不同用户与团队互不干扰() {
        cache.put(1L, 2L, "a", true);
        cache.put(1L, 3L, "a", false);
        cache.put(9L, 2L, "a", false);

        assertTrue(cache.getIfPresent(1L, 2L, "a"));
        assertFalse(cache.getIfPresent(1L, 3L, "a"));
        assertFalse(cache.getIfPresent(9L, 2L, "a"));
    }

    @Test
    void 失效成员只清该成员() {
        cache.put(1L, 2L, "a", true);
        cache.put(1L, 3L, "a", true);

        cache.invalidateMember(1L, 2L);

        assertNull(cache.getIfPresent(1L, 2L, "a"), "该成员应被清除");
        assertEquals(Boolean.TRUE, cache.getIfPresent(1L, 3L, "a"), "同队其他成员不应受影响");
    }

    @Test
    void 失效团队清掉该队所有成员() {
        cache.put(1L, 2L, "a", true);
        cache.put(1L, 3L, "b", true);
        cache.put(9L, 2L, "a", true);

        cache.invalidateTeam(1L);

        assertNull(cache.getIfPresent(1L, 2L, "a"));
        assertNull(cache.getIfPresent(1L, 3L, "b"));
        assertEquals(Boolean.TRUE, cache.getIfPresent(9L, 2L, "a"), "其他团队不应受影响");
    }

    @Test
    void 参数为空时不缓存也不报错() {
        cache.put(null, 2L, "a", true);
        cache.put(1L, null, "a", true);
        cache.put(1L, 2L, null, true);
        cache.put(1L, 2L, "", true);

        assertNull(cache.getIfPresent(null, 2L, "a"));
        assertNull(cache.getIfPresent(1L, null, "a"));
        assertNull(cache.getIfPresent(1L, 2L, ""));
        assertEquals(0L, cache.size(), "非法参数不应写入缓存");
    }
}
