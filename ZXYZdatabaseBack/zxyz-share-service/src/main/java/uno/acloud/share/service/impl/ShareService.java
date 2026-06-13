package uno.acloud.share.service.impl;

import org.springframework.stereotype.Service;
import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.service.SharePort;
import uno.acloud.share.service.model.ShareVerifyResult;
import uno.acloud.share.vo.ShareCreateResponse;
import uno.acloud.share.vo.ShareDownloadResponseVO;
import uno.acloud.share.vo.ShareFilesResponseItemVO;
import uno.acloud.share.vo.ShareMyListItemVO;
import uno.acloud.share.vo.ShareMyListResponseVO;
import uno.acloud.share.vo.SharePublicInfoVO;

import java.util.List;

@Service
public class ShareService implements SharePort {

    private final ShareManager shareManageService;
    private final ShareAccessManager shareAccessService;
    private final ShareContentProvider shareContentService;

    public ShareService(ShareManager shareManageService,
                            ShareAccessManager shareAccessService,
                            ShareContentProvider shareContentService) {
        this.shareManageService = shareManageService;
        this.shareAccessService = shareAccessService;
        this.shareContentService = shareContentService;
    }

    @Override
    public ShareCreateResponse createShare(ShareCreateRequest request, Long userId) {
        return shareManageService.createShare(request, userId);
    }

    @Override
    public ShareMyListResponseVO getMyShares(Long userId, Integer page, Integer pageSize) {
        return shareManageService.getMyShares(userId, page, pageSize);
    }

    @Override
    public ShareMyListItemVO getShareDetail(Long shareId, Long userId) {
        return shareManageService.getShareDetail(shareId, userId);
    }

    @Override
    public void cancelShare(Long shareId, Long userId) {
        shareManageService.cancelShare(shareId, userId);
    }

    @Override
    public ShareMyListItemVO updateShareStatus(Long shareId, Integer status, Long userId) {
        return shareManageService.updateShareStatus(shareId, status, userId);
    }

    @Override
    public ShareVerifyResult verifyShare(ShareVerifyRequest request, String shareAccessToken) {
        return shareAccessService.verifyShare(request, shareAccessToken);
    }

    @Override
    public SharePublicInfoVO getPublicShareInfo(String shareKey, String shareAccessToken) {
        return shareAccessService.getPublicShareInfo(shareKey, shareAccessToken);
    }

    @Override
    public List<ShareFilesResponseItemVO> getShareFiles(String shareKey, String path, String shareAccessToken) {
        return shareContentService.getShareFiles(shareKey, path, shareAccessToken);
    }

    @Override
    public ShareDownloadResponseVO getShareDownloadUrl(String shareKey, Long fileId, String shareAccessToken) {
        return shareContentService.getShareDownloadUrl(shareKey, fileId, shareAccessToken);
    }
}
