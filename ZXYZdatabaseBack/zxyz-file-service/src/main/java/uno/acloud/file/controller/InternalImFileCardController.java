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
import uno.acloud.file.web.ActingUserIdResolver;

/**
 * IM 文件卡片内部端点（im-service → file-service）。
 *
 * <p>P1-1（ISSUE/54 §2.7）修复：本链路只做内部 token 鉴权（InternalServiceAuthInterceptor），
 * <b>不存在用户会话</b> —— 端点内不能再用 Sa-Token 语义取当前用户。
 * 操作者身份改由调用方通过 {@code X-Acting-User-Id} 请求头显式传递
 * （im 侧从 WS 握手 / HTTP 拦截器上下文取得真实 userId），
 * 由 {@link ActingUserIdResolver} 校验「头存在且为合法 Long」，
 * 缺失或非法 → {@code BusinessException}(BAD_REQUEST) 明确报错。</p>
 */
@Hidden
@RestController
@RequestMapping("/api/internal/im-file-cards")
@Tag(name = "IM文件卡片（内部）", description = "内部服务文件卡片 API")
public class InternalImFileCardController {

    private final ImFileCardPort imFileCardPort;
    private final ActingUserIdResolver actingUserIdResolver;

    public InternalImFileCardController(ImFileCardPort imFileCardPort,
                                        ActingUserIdResolver actingUserIdResolver) {
        this.imFileCardPort = imFileCardPort;
        this.actingUserIdResolver = actingUserIdResolver;
    }

    @Operation(summary = "创建文件卡片快照（内部）")
    @PostMapping("/snapshot")
    public Result<FileCardSnapshotVO> snapshot(@Valid @RequestBody FileCardSnapshotRequest request) {
        return Result.of(imFileCardPort.createSnapshot(request, actingUserIdResolver.requireActingUserId()));
    }

    @Operation(summary = "解析文件卡片（内部）")
    @PostMapping("/resolve")
    public Result<FileCardResolveVO> resolve(@Valid @RequestBody FileCardResolveRequest request) {
        return Result.of(imFileCardPort.resolve(request, actingUserIdResolver.requireActingUserId()));
    }
}
