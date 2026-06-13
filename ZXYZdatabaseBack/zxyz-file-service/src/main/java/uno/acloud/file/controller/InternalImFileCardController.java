package uno.acloud.file.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.file.dto.im.FileCardResolveRequest;
import uno.acloud.file.dto.im.FileCardSnapshotRequest;
import uno.acloud.file.service.ImFileCardPort;
import uno.acloud.file.vo.im.FileCardResolveVO;
import uno.acloud.file.vo.im.FileCardSnapshotVO;

@Hidden
@RestController
@RequestMapping("/api/internal/im-file-cards")
@Tag(name = "IM文件卡片（内部）", description = "内部服务文件卡片 API")
public class InternalImFileCardController {

    private final ImFileCardPort imFileCardPort;

    public InternalImFileCardController(ImFileCardPort imFileCardPort) {
        this.imFileCardPort = imFileCardPort;
    }

    @Operation(summary = "创建文件卡片快照（内部）")
    @PostMapping("/snapshot")
    public Result<FileCardSnapshotVO> snapshot(@Valid @RequestBody FileCardSnapshotRequest request) {
        return Result.of(imFileCardPort.createSnapshot(request));
    }

    @Operation(summary = "解析文件卡片（内部）")
    @PostMapping("/resolve")
    public Result<FileCardResolveVO> resolve(@Valid @RequestBody FileCardResolveRequest request) {
        return Result.of(imFileCardPort.resolve(request));
    }
}
