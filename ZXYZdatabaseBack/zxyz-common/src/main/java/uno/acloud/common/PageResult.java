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
 * <p>字段固定为 {@code page} / {@code pageSize} / {@code total} / {@code list}，前端因此可以复用
 * 同一套解析逻辑（见 {@code api/files.ts::fetchFileList} 里对 {@code {list,total}} 信封的兼容代码），
 * 新接口不必再各自约定字段名。
 *
 * <p><b>全库唯一的分页信封</b>：file-service 原先自带的 {@code FileListPagedResultVO} 与
 * {@code FileSearchResultVO}（后者连 {@code page} / {@code pageSize} 都没有）、email-service 的
 * {@code EmailRecordPageVO}（列表字段叫 {@code records}）已于 07-P2-4 全部并入本类。
 * 列表字段名此后只有 {@code list} 一个。
 *
 * <p><b>默认页长与上限是两件事</b>：默认页长按接口历史口径各自不同（空间文件列表 50、
 * 文件搜索 20、我的分享 10），用 {@link #normalizePageSize(Integer, int)} 传；上限则必须全库唯一，
 * 见 {@link #MAX_PAGE_SIZE}。
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

    /** 归一化每页条数：{@code null} 或小于 1 取 {@link #DEFAULT_PAGE_SIZE}，超过上限则钳制到上限。 */
    public static int normalizePageSize(Integer pageSize) {
        return normalizePageSize(pageSize, DEFAULT_PAGE_SIZE);
    }

    /**
     * 归一化每页条数，默认值由调用方按接口的历史口径指定。
     *
     * <p><b>为什么需要这个重载</b>：各接口的历史默认页长本就不同（空间文件列表 50、
     * 文件搜索 20、我的分享 10），这些默认值不能统一；但<b>上限只能有一处</b>。
     * 此前 file-service 与 email-service 各自硬编码上限（100 / 50），而前端 el-pagination 的
     * 页长选项最大是 200（{@code constants/pagination.js::SPACE_PAGE_SIZE_OPTIONS}）——
     * 用户选 200 时后端按 100 或 50 分页、前端却按 200 算总页数，
     * 两者不一致会让<b>中后段数据永远翻不到</b>。
     *
     * <p>统一走本方法后上限恒为 {@link #MAX_PAGE_SIZE}，与前端选项上界一致。
     * 前端另有「按响应回传的 pageSize 校准」作第二道防线（见 {@code useSpaceFileList}）。
     *
     * @param pageSize        原始每页条数，{@code null} 或小于 1 时取 {@code defaultPageSize}
     * @param defaultPageSize 该接口的历史默认页长
     * @return 归一化后的页长，取值范围 [1, MAX_PAGE_SIZE]
     */
    public static int normalizePageSize(Integer pageSize, int defaultPageSize) {
        if (pageSize == null || pageSize < 1) {
            return defaultPageSize;
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
