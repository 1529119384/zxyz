package uno.acloud.share.service.impl;

import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.UserErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.ShareUserQueryClient;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
import uno.acloud.vo.InternalUserInfoVO;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ShareValidator {

    private final ShareFileServiceClient shareFileServiceClient;
    private final ShareUserQueryClient shareUserQueryClient;

    public ShareValidator(ShareFileServiceClient shareFileServiceClient,
                          ShareUserQueryClient shareUserQueryClient) {
        this.shareFileServiceClient = shareFileServiceClient;
        this.shareUserQueryClient = shareUserQueryClient;
    }

    public void validateUserId(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.NO_LOGIN, "用户未登录");
        }
    }

    public InternalUserInfoVO requireUser(Long userId) {
        InternalUserInfoVO user = shareUserQueryClient.getUserInfo(userId);
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public Map<Long, ShareFileProjection> requireActiveFiles(List<Long> fileIds) {
        Map<Long, ShareFileProjection> fileInfoMap = shareFileServiceClient.getShareFileProjections(fileIds).stream()
                .collect(Collectors.toMap(ShareFileProjection::getId, fileInfo -> fileInfo));
        for (Long fileId : fileIds) {
            ShareFileProjection fileInfo = fileInfoMap.get(fileId);
            if (fileInfo == null || !isActive(fileInfo)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "所选文件中包含已删除或不存在的数据");
            }
        }
        return fileInfoMap;
    }

    /**
     * 校验所选文件对指定用户均有读权限（P0-3 防 IDOR）。
     * <p>调用 file-service 内部窄端点，杜绝为他人文件创建公开分享。</p>
     */
    public void requireShareFileAccess(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        shareFileServiceClient.checkShareFileAccess(fileIds, userId);
    }

    public boolean isActive(ShareFileProjection fileInfo) {
        return fileInfo != null && fileInfo.isActive();
    }
}
