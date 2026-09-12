package uno.acloud.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * 通用分页信封。
 *
 * <p>字段与既有的 {@code FileListPagedResultVO} / {@code FileSearchResultVO} 保持一致
 * （{@code page} / {@code pageSize} / {@code total} / {@code list}），前端因此可以复用同一套
 * 解析逻辑（见 {@code api/files.ts::fetchFileList} 里对 {@code {list,total}} 信封的兼容代码），
 * 新接口不必再各自约定字段名。
 *
 * <p>放在 {@code zxyz-common} 而不是每个服务各写一个 VO：分页信封跨服务是同一种形状，
 * 逐接口复制只会持续放大重复代码（见 07 文档「重复代码」一节）。
 *
 * <p><b>为什么必须带上限</b>：{@link #MAX_PAGE_SIZE} 是这类接口的兜底闸门。没有它，
 * 调用方传一个极大的 {@code pageSize} 就等于把接口恢复成"全表查询"，
 * 本类型存在的意义（把不可控的返回体变成可控）会被直接绕过。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class PageResult<T> {

    /** 未显式指定 pageSize 时的默认每页条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限：防止调用方用超大 pageSize 退化成全表查询。 */
    public static final int MAX_PAGE_SIZE = 200;

    @Schema(description = "当前页码", example = "1")
    private Integer page;

    @Schema(description = "每页大小", example = "20")
    private Integer pageSize;

    @Schema(description = "记录总数", example = "100")
    private Long total;

    @Schema(description = "当前页记录")
    private List<T> list;

    /** 归一化页码：{@code null} 或小于 1 一律回落到第 1 页。 */
    public static int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    /** 归一化每页条数：{@code null} 或小于 1 取默认值，超过上限则钳制到上限。 */
    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 由页码与每页条数算出 SQL 的 OFFSET。 */
    public static int offsetOf(int page, int pageSize) {
        return (page - 1) * pageSize;
    }

    /** 组装分页结果；{@code list} 为 {@code null} 时统一落成空列表，避免前端拿到 {@code null}。 */
    public static <T> PageResult<T> of(int page, int pageSize, long total, List<T> list) {
        return new PageResult<>(page, pageSize, total, list == null ? List.of() : list);
    }
}
