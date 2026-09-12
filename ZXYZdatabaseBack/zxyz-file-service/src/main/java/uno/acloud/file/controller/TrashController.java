package uno.acloud.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import uno.acloud.common.PageResult;
import uno.acloud.common.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.vo.FileListItemVO;

@RestController
@RequestMapping("/api/trash")
@Tag(name = "回收站", description = "回收站文件列表")
public class TrashController {

    private final FileQueryPort fileQueryPort;

    public TrashController(FileQueryPort fileQueryPort) {
        this.fileQueryPort = fileQueryPort;
    }

    /**
     * 回收站文件列表（分页）。
     *
     * <p>此前该接口无分页、无条数上限，回收站随删除操作持续增长（见
     * 07-CODE-QUALITY-REVIEW.md 的 P0-2）。现在 {@code pageSize} 会被
     * {@link PageResult#normalizePageSize} 钳制到 {@link PageResult#MAX_PAGE_SIZE}，
     * 调用方无法再用超大值把接口当成全量导出来用。
     */
    @Operation(summary = "获取回收站文件列表")
    @GetMapping("/files")
    @SaCheckPermission(SystemPermissionCodes.TRASH_READ)
    public Result<PageResult<FileListItemVO>> listTrashFiles(@CurrentUser Long userId,
                                 @RequestParam(required = false) Long teamId,
                                 @RequestParam(required = false) Integer spaceType,
                                 @RequestParam(required = false) Long projectId,
                                 @Parameter(description = "页码，从 1 起")
                                 @RequestParam(defaultValue = "1") Integer page,
                                 @Parameter(description = "每页条数，上限 " + PageResult.MAX_PAGE_SIZE
                                         + "，默认 " + PageResult.DEFAULT_PAGE_SIZE)
                                 @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.of(fileQueryPort.getRecycleList(teamId, spaceType, projectId, userId, page, pageSize));
    }
}
