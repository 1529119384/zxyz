package uno.acloud.share.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.share.service.impl.ShareManager;

import java.util.List;

/**
 * 内部服务 API，仅供其他微服务通过 INTERNAL_SERVICE_TOKEN 调用。
 */
@RestController
@RequestMapping("/api/internal/shares")
@Tag(name = "分享管理（内部）", description = "内部服务分享清理 API")
@Hidden
public class InternalShareController {

    private final ShareManager shareManager;

    public InternalShareController(ShareManager shareManager) {
        this.shareManager = shareManager;
    }

    @PostMapping("/cleanup-by-files")
    @Operation(summary = "按文件ID清理分享项")
    public Result<Integer> cleanupShareItemsByFileIds(@Valid @RequestBody FileIdsRequest request) {
        return Result.of(shareManager.cleanupShareItemsByFileIds(request.getFileIds()));
    }

    @Getter
    @Setter
    @ToString
    public static class FileIdsRequest {
        @NotEmpty(message = "文件ID列表不能为空")
        private List<Long> fileIds;
    }
}
