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
