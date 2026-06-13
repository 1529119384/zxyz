package uno.acloud.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import uno.acloud.common.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.TeamPermissionCodes;
import uno.acloud.common.permission.RequiresTeamPermission;
import uno.acloud.file.dto.CreateFolderRequest;
import uno.acloud.file.service.FileFolderPort;
import uno.acloud.file.vo.FolderCreateResultVO;

@RestController
@RequestMapping("/api/folders")
@Tag(name = "文件夹管理", description = "文件夹创建")
public class FolderController {

    private final FileFolderPort fileFolderPort;

    public FolderController(FileFolderPort fileFolderPort) {
        this.fileFolderPort = fileFolderPort;
    }

    @Operation(summary = "创建文件夹")
    @PostMapping
    @SaCheckPermission(SystemPermissionCodes.FOLDER_CREATE)
    @RequiresTeamPermission(value = TeamPermissionCodes.TEAM_FILE_WRITE, teamIdArg = "request.teamId")
    public Result<FolderCreateResultVO> createFolder(@CurrentUser Long userId, @Valid @RequestBody CreateFolderRequest request) {
        return Result.of(fileFolderPort.createFolder(
                request.getFolderName(),
                request.getParentId(),
                request.getTeamId(),
                request.getSpaceType(),
                request.getProjectId(),
                userId
        ));
    }
}
