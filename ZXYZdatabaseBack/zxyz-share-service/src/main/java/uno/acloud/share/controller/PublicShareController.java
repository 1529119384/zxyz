package uno.acloud.share.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uno.acloud.common.Result;
import uno.acloud.share.controller.support.ShareCookieManager;
import uno.acloud.share.dto.ShareAccessRequest;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.service.SharePort;
import uno.acloud.share.service.impl.ShareAccessRateLimiter;
import uno.acloud.share.service.model.ShareVerifyResult;
import uno.acloud.share.vo.ShareDownloadResponseVO;
import uno.acloud.share.vo.ShareFilesResponseItemVO;
import uno.acloud.share.vo.SharePublicInfoVO;
import uno.acloud.share.vo.ShareVerifyResponseVO;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/public/shares")
@Tag(name = "公开分享", description = "公开分享页面访问（无需登录）")
public class PublicShareController {

    private final SharePort shareService;
    private final ShareCookieManager shareCookieManager;
    private final ShareAccessRateLimiter rateLimiter;

    @PostMapping("/{shareKey}/accesses")
    @Operation(summary = "验证分享密码")
    public Result<ShareVerifyResponseVO> verifyShare(@PathVariable String shareKey,
                              @Valid @RequestBody ShareAccessRequest request,
                              HttpServletRequest httpServletRequest,
                              HttpServletResponse httpServletResponse) {
        String clientIp = httpServletRequest.getRemoteAddr();
        rateLimiter.checkAndIncrement(shareKey, clientIp);
        ShareVerifyRequest verifyRequest = new ShareVerifyRequest();
        verifyRequest.setShareKey(shareKey);
        verifyRequest.setPassword(request == null ? null : request.getPassword());
        String shareAccessToken = shareCookieManager.resolveAccessToken(shareKey, httpServletRequest);
        ShareVerifyResult verifyResult = shareService.verifyShare(verifyRequest, shareAccessToken);
        shareCookieManager.writeAccessToken(shareKey, verifyResult.getAccessToken(), verifyResult.getExpireTime(), httpServletResponse);
        return Result.of(verifyResult.getResponse());
    }

    @GetMapping("/{shareKey}")
    @Operation(summary = "获取公开分享信息")
    public Result<SharePublicInfoVO> getPublicShareInfo(@PathVariable String shareKey,
                                     HttpServletRequest httpServletRequest) {
        String shareAccessToken = shareCookieManager.resolveAccessToken(shareKey, httpServletRequest);
        return Result.of(shareService.getPublicShareInfo(shareKey, shareAccessToken));
    }

    @GetMapping("/{shareKey}/files")
    @Operation(summary = "获取分享文件列表")
    public Result<List<ShareFilesResponseItemVO>> getShareFiles(@PathVariable String shareKey,
                                @RequestParam(required = false) String path,
                                HttpServletRequest httpServletRequest) {
        String shareAccessToken = shareCookieManager.resolveAccessToken(shareKey, httpServletRequest);
        return Result.of(shareService.getShareFiles(shareKey, path, shareAccessToken));
    }

    @GetMapping("/{shareKey}/files/{fileId}/download-url")
    @Operation(summary = "获取分享文件下载链接")
    public Result<ShareDownloadResponseVO> getShareDownloadUrl(@PathVariable String shareKey,
                                      @PathVariable Long fileId,
                                      HttpServletRequest httpServletRequest) {
        String shareAccessToken = shareCookieManager.resolveAccessToken(shareKey, httpServletRequest);
        return Result.of(shareService.getShareDownloadUrl(shareKey, fileId, shareAccessToken));
    }
}
