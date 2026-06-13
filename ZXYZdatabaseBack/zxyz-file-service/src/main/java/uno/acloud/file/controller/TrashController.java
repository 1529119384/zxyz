package uno.acloud.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import uno.acloud.common.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.file.service.FileQueryPort;
import uno.acloud.file.vo.FileListItemVO;

import java.util.List;

@RestController
@RequestMapping("/api/trash")
@Tag(name = "回收站", description = "回收站文件列表")
public class TrashController {

    private final FileQueryPort fileQueryPort;

    public TrashController(FileQueryPort fileQueryPort) {
        this.fileQueryPort = fileQueryPort;
    }

    @Operation(summary = "获取回收站文件列表")
    @GetMapping("/files")
    @SaCheckPermission(SystemPermissionCodes.TRASH_READ)
    public Result<List<FileListItemVO>> listTrashFiles(@CurrentUser Long userId,
                                 @RequestParam(required = false) Long teamId,
                                 @RequestParam(required = false) Integer spaceType,
                                 @RequestParam(required = false) Long projectId) {
        return Result.of(fileQueryPort.getRecycleList(teamId, spaceType, projectId, userId));
    }
}
