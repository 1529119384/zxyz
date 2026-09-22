package uno.acloud.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 项目维度存储用量（按 projectId 分组的一条聚合结果）。
 *
 * <p>与 {@link TeamStorageUsage} / {@link PersonalStorageUsage} 同族：都是「一次调用换回 N 条
 * {@code (维度 id, usedStorage)}」，用来把列表页的 N+1 次远程查询压成 1 次。</p>
 *
 * <p>⚠️ 分组查询「没有匹配行 ⇒ 结果里没有这一条」。消费端必须把「缺失」按 <b>0</b> 处理 ——
 * 这与单值版 {@code sumActiveFileSize} 的行为一致（单值版是 {@code COALESCE(SUM(...), 0)}，
 * 零行时仍返回一行 0）。若把缺失当成 null 或抛错，列表页就会出现「明明是空项目却报错」。</p>
 */
@Getter
@Setter
@ToString
@Schema(description = "项目存储用量")
public class ProjectStorageUsage implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "项目ID")
    private Long projectId;

    @Schema(description = "已使用存储（字节）")
    private long usedStorage;
}
