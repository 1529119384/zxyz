package uno.acloud.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageResultTest {

    // ---- normalizePage ----

    @Test
    void normalizePageShouldFallbackToOneForNullOrNonPositive() {
        assertEquals(1, PageResult.normalizePage(null));
        assertEquals(1, PageResult.normalizePage(0));
        assertEquals(1, PageResult.normalizePage(-5));
    }

    @Test
    void normalizePageShouldKeepValidPage() {
        assertEquals(1, PageResult.normalizePage(1));
        assertEquals(7, PageResult.normalizePage(7));
    }

    // ---- normalizePageSize ----

    @Test
    void normalizePageSizeShouldFallbackToDefaultForNullOrNonPositive() {
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, PageResult.normalizePageSize(null));
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, PageResult.normalizePageSize(0));
        assertEquals(PageResult.DEFAULT_PAGE_SIZE, PageResult.normalizePageSize(-1));
    }

    @Test
    void normalizePageSizeShouldKeepValueWithinLimit() {
        assertEquals(1, PageResult.normalizePageSize(1));
        assertEquals(50, PageResult.normalizePageSize(50));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(PageResult.MAX_PAGE_SIZE));
    }

    /**
     * 这是本类型最关键的约束：没有这道钳制，调用方传一个极大的 pageSize
     * 就能让接口退化成"全表查询"，等于绕过分页。
     */
    @Test
    void normalizePageSizeShouldClampOversizedValue() {
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(PageResult.MAX_PAGE_SIZE + 1));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(10_000));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(Integer.MAX_VALUE));
    }

    @Test
    void defaultPageSizeShouldNotExceedMaxPageSize() {
        assertTrue(PageResult.DEFAULT_PAGE_SIZE > 0);
        assertTrue(PageResult.DEFAULT_PAGE_SIZE <= PageResult.MAX_PAGE_SIZE);
    }

    // ---- normalizePageSize(pageSize, defaultPageSize) ----
    //
    // 07-P2-4 新增的两参重载，也是修掉「页长上限错位」的那一处：
    // 此前 file-service 把上限硬编码成 100、文件搜索 50，而前端 el-pagination 的页长
    // 选项上界是 200 —— 用户选 200 时后端按更小的值分页、前端却按 200 算总页数，
    // 两者不一致会让中后段数据永远翻不到。
    // 该重载让「默认页长」按接口历史口径各自保留、「上限」全库唯一。

    @Test
    void normalizePageSizeWithDefaultShouldKeepEachEndpointDefault() {
        // 三个真实调用点的历史默认页长：空间文件列表 50 / 文件搜索 20 / 我的分享 10
        assertEquals(50, PageResult.normalizePageSize(null, 50));
        assertEquals(20, PageResult.normalizePageSize(null, 20));
        assertEquals(10, PageResult.normalizePageSize(null, 10));
        // 小于 1 与 null 同义：回落到该接口的默认页长，而不是公共默认值
        assertEquals(50, PageResult.normalizePageSize(0, 50));
        assertEquals(50, PageResult.normalizePageSize(-3, 50));
    }

    /**
     * 关键回归：前端页长选项上界是 200，后端必须原样接受 200，
     * 而不是被旧的自造上限（file-service 100 / 文件搜索 50）截断。
     */
    @Test
    void normalizePageSizeWithDefaultShouldAcceptTheSharedMaximum() {
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(200, 50));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(200, 20));
        assertEquals(100, PageResult.normalizePageSize(100, 50));
        assertEquals(1, PageResult.normalizePageSize(1, 50));
    }

    /** 超过上限仍必须兜住 —— 否则调用方传个极大的 pageSize 就能让接口退化成全表查询。 */
    @Test
    void normalizePageSizeWithDefaultShouldClampOversizedValue() {
        assertEquals(
                PageResult.MAX_PAGE_SIZE,
                PageResult.normalizePageSize(PageResult.MAX_PAGE_SIZE + 1, 10));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(10_000, 50));
        assertEquals(PageResult.MAX_PAGE_SIZE, PageResult.normalizePageSize(Integer.MAX_VALUE, 20));
    }

    /** 单参重载必须与「两参 + DEFAULT_PAGE_SIZE」逐值同构，否则两条路径会悄悄分叉。 */
    @Test
    void normalizePageSizeWithoutDefaultShouldDelegateToTheSharedDefault() {
        Integer[] candidates = {null, 0, -1, 1, 20, 100, 200, 201, Integer.MAX_VALUE};
        for (Integer candidate : candidates) {
            assertEquals(
                    PageResult.normalizePageSize(candidate, PageResult.DEFAULT_PAGE_SIZE),
                    PageResult.normalizePageSize(candidate),
                    "pageSize=" + candidate);
        }
    }

    // 说明（刻意不断言，仅记录现状）：本重载只钳制**入参**一侧，调用方传入的
    // `defaultPageSize` 会原样返回（例如 normalizePageSize(null, 500) == 500）。
    // 当前三个调用点分别传 50 / 20 / 10，都在 MAX_PAGE_SIZE 之内，故无实际影响；
    // 新增调用点时不要传一个超过 MAX_PAGE_SIZE 的默认值。

    // ---- offsetOf ----

    @Test
    void offsetOfShouldBeZeroBased() {
        assertEquals(0, PageResult.offsetOf(1, 20));
        assertEquals(20, PageResult.offsetOf(2, 20));
        assertEquals(40, PageResult.offsetOf(3, 20));
    }

    // ---- of ----

    @Test
    void ofShouldKeepFieldsAndList() {
        PageResult<String> result = PageResult.of(2, 10, 35L, List.of("a", "b"));

        assertEquals(2, result.getPage());
        assertEquals(10, result.getPageSize());
        assertEquals(35L, result.getTotal());
        assertEquals(List.of("a", "b"), result.getList());
    }

    /**
     * 空页在"越界页码"和"确实没有数据"两种情况下都会出现，
     * 统一落成空列表可以让前端只判断 length，不必到处防 null。
     */
    @Test
    void ofShouldConvertNullListToEmptyList() {
        PageResult<String> result = PageResult.of(1, 20, 0L, null);

        assertEquals(List.of(), result.getList());
        assertEquals(0L, result.getTotal());
    }

    @Test
    void noArgsConstructorShouldProduceMutableEnvelope() {
        PageResult<Integer> result = new PageResult<>();
        result.setPage(1);
        result.setPageSize(20);
        result.setTotal(1L);
        result.setList(List.of(42));

        assertEquals(List.of(42), result.getList());
        assertEquals(1, result.getPage());
    }
}
