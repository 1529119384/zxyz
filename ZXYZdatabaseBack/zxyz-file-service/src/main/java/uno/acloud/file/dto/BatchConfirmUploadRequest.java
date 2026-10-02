package uno.acloud.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.List;

@Getter
@Setter
@ToString
@Schema(description = "批量确认上传请求")
public class BatchConfirmUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 单次批量确认的条目上限（F5，P2 无界输入）。
     *
     * <p><b>为什么必须有上限</b>：{@code FileUploadService.confirmUpload} 对每一项串行执行
     * 「OSS HEAD（远程）+ DB 写 + 唯一名重试」，单请求 1 万项就是 1 万次串行远程调用 ——
     * 请求必然超时，同时长时间占满工作线程与 DB 连接，拖垮整个服务。</p>
     *
     * <p><b>为什么是 50</b>：与复制路径已有的 500 不同，确认上传是<b>每项一次远程 HEAD</b>，
     * 成本远高于复制的一次本地 INSERT；前端上传并发批次也远小于此。50 在「一次大目录上传」
     * 与「单请求可控」之间取平衡，且远超前端实际上传批的大小。</p>
     *
     * <p>服务端在 {@code FileUploadService#confirmUpload} 入口还有一道显式校验 ——
     * Bean Validation 只覆盖走 {@code @Valid} 的 HTTP 路径，内部/测试直调服务层不受其保护。</p>
     */
    public static final int MAX_FILES_PER_BATCH = 50;

    @Schema(description = "团队ID")
    private Long teamId;

    @NotNull(message = "空间类型不能为空")
    @Schema(description = "空间类型：1-个人，2-团队，3-项目", example = "1")
    private Integer spaceType;

    @Schema(description = "项目ID，仅项目空间需要")
    private Long projectId;

    @Valid
    @NotEmpty(message = "文件列表不能为空")
    @Size(max = MAX_FILES_PER_BATCH, message = "单次批量确认上传的文件数不能超过 " + MAX_FILES_PER_BATCH)
    @Schema(description = "待确认的文件列表")
    private List<ConfirmUploadRequest> files;
}
