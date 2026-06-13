package uno.acloud.share.service.impl;

import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.client.ShareFileServiceClient;
import uno.acloud.share.infrastructure.client.ShareUserQueryClient;
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
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    public Map<Long, FileInfoDTO> requireActiveFiles(List<Long> fileIds) {
        Map<Long, FileInfoDTO> fileInfoMap = shareFileServiceClient.getFileInfoByIds(fileIds).stream()
                .collect(Collectors.toMap(FileInfoDTO::getId, fileInfo -> fileInfo));
        for (Long fileId : fileIds) {
            FileInfoDTO fileInfo = fileInfoMap.get(fileId);
            if (fileInfo == null || !isActive(fileInfo)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "所选文件中包含已删除或不存在的数据");
            }
        }
        return fileInfoMap;
    }

    public boolean isActive(FileInfoDTO fileInfo) {
        return fileInfo != null && fileInfo.isActive();
    }
}
