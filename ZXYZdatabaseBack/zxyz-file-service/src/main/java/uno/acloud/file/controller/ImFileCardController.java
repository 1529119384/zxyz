package uno.acloud.file.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.service.ImFileCardPort;
import uno.acloud.file.vo.im.FileCardResolveVO;
import uno.acloud.file.vo.im.FileCardSnapshotVO;

@RestController
@RequestMapping("/api/im-file-cards")
@Tag(name = "IM文件卡片", description = "即时通讯文件卡片快照与解析")
public class ImFileCardController {

    private final ImFileCardPort imFileCardPort;

    public ImFileCardController(ImFileCardPort imFileCardPort) {
        this.imFileCardPort = imFileCardPort;
    }

    @Operation(summary = "创建文件卡片快照")
    @PostMapping("/snapshot")
    @SaCheckPermission(SystemPermissionCodes.IM_FILE_CARD)
    public Result<FileCardSnapshotVO> snapshot(@Valid @RequestBody FileCardSnapshotRequest request) {
        return Result.of(imFileCardPort.createSnapshot(request));
    }

    @Operation(summary = "解析文件卡片")
    @PostMapping("/resolve")
    @SaCheckPermission(SystemPermissionCodes.IM_FILE_CARD)
    public Result<FileCardResolveVO> resolve(@Valid @RequestBody FileCardResolveRequest request) {
        return Result.of(imFileCardPort.resolve(request));
    }
}
