package uno.acloud.project.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.project.dto.internal.CheckUploadQuotaRequest;
import uno.acloud.project.service.StorageQuotaPort;

/**
 * 内部存储配额 API，供 file-service 通过 INTERNAL_SERVICE_TOKEN 调用。
 */
@Hidden
@RestController
@RequestMapping("/api/internal/storage")
@Tag(name = "存储配额（内部）", description = "内部服务存储配额检查 API")
public class InternalStorageController {

    private final StorageQuotaPort storageQuotaPort;

    public InternalStorageController(StorageQuotaPort storageQuotaPort) {
        this.storageQuotaPort = storageQuotaPort;
    }

    @Operation(summary = "检查上传配额")
    @PostMapping("/check-quota")
    public Result<Void> checkUploadQuota(@Valid @RequestBody CheckUploadQuotaRequest body) {
        storageQuotaPort.checkUploadQuota(
                body.userId(), body.teamId(), body.spaceType(), body.projectId(),
                body.totalSize() == null ? 0 : body.totalSize());
        return Result.success();
    }
}
