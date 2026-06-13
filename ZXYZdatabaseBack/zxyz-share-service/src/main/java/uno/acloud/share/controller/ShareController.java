package uno.acloud.share.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.common.SystemPermissionCodes;
import uno.acloud.common.web.CurrentUser;
import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.share.dto.ShareUpdateRequest;
import uno.acloud.share.service.SharePort;
import uno.acloud.share.vo.ShareCreateResponse;
import uno.acloud.share.vo.ShareMyListItemVO;
import uno.acloud.share.vo.ShareMyListResponseVO;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/shares")
@Tag(name = "分享管理", description = "分享链接创建、查询、更新")
public class ShareController {

    private final SharePort shareService;

    @PostMapping
    @Operation(summary = "创建分享链接")
    @SaCheckPermission(SystemPermissionCodes.SHARE_CREATE)
    public Result<ShareCreateResponse> createShare(@CurrentUser Long userId, @Valid @RequestBody ShareCreateRequest request) {
        log.info("用户 {} 创建分享，文件数：{}", userId, request == null ? 0 : (request.getFileIds() == null ? 0 : request.getFileIds().size()));
        return Result.of(shareService.createShare(request, userId));
    }

    @GetMapping
    @Operation(summary = "查询我的分享列表")
    @SaCheckPermission(SystemPermissionCodes.SHARE_READ)
    public Result<ShareMyListResponseVO> getMyShares(@CurrentUser Long userId,
                              @RequestParam(required = false, defaultValue = "1") Integer page,
                              @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        return Result.of(shareService.getMyShares(userId, page, pageSize));
    }

    @GetMapping("/{shareId}")
    @Operation(summary = "获取分享详情")
    @SaCheckPermission(SystemPermissionCodes.SHARE_READ)
    public Result<ShareMyListItemVO> getShareDetail(@CurrentUser Long userId, @PathVariable Long shareId) {
        return Result.of(shareService.getShareDetail(shareId, userId));
    }

    @PatchMapping("/{shareId}")
    @Operation(summary = "更新分享设置")
    @SaCheckPermission(SystemPermissionCodes.SHARE_MANAGE)
    public Result<ShareMyListItemVO> updateShare(@CurrentUser Long userId, @PathVariable Long shareId, @Valid @RequestBody ShareUpdateRequest request) {
        return Result.of(shareService.updateShareStatus(shareId, request == null ? null : request.getStatus(), userId));
    }
}
