package uno.acloud.share.service;

import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.service.model.ShareVerifyResult;
import uno.acloud.share.vo.ShareCreateResponse;
import uno.acloud.share.vo.ShareDownloadResponseVO;
import uno.acloud.share.vo.ShareFilesResponseItemVO;
import uno.acloud.share.vo.ShareMyListItemVO;
import uno.acloud.share.vo.ShareMyListResponseVO;
import uno.acloud.share.vo.SharePublicInfoVO;
import uno.acloud.share.vo.ShareVerifyResponseVO;

import java.util.List;

public interface SharePort {
    ShareCreateResponse createShare(ShareCreateRequest request, Long userId);

    ShareMyListResponseVO getMyShares(Long userId, Integer page, Integer pageSize);

    ShareMyListItemVO getShareDetail(Long shareId, Long userId);

    void cancelShare(Long shareId, Long userId);

    ShareMyListItemVO updateShareStatus(Long shareId, Integer status, Long userId);

    ShareVerifyResult verifyShare(ShareVerifyRequest request, String shareAccessToken);

    SharePublicInfoVO getPublicShareInfo(String shareKey, String shareAccessToken);

    List<ShareFilesResponseItemVO> getShareFiles(String shareKey, String path, String shareAccessToken);

    ShareDownloadResponseVO getShareDownloadUrl(String shareKey, Long fileId, String shareAccessToken);

    /**
     * 获取分享文件的流式下载 URL（本地存储等非预签名提供者使用）
     */
    ShareDownloadResponseVO getShareStreamUrl(String shareKey, Long fileId, String shareAccessToken);
}
