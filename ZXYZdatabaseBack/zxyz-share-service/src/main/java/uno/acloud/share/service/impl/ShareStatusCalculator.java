package uno.acloud.share.service.impl;

import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.common.ShareStatusMeta;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.time.LocalDateTime;
import java.util.Objects;

@Component
public class ShareStatusCalculator {

    private final ShareMapper shareMapper;

    public ShareStatusCalculator(ShareMapper shareMapper) {
        this.shareMapper = shareMapper;
    }

    public Share refreshStatusIfNeeded(Share share) {
        int nextStatus = calculateShareStatus(share);
        if (!Objects.equals(share.getStatus(), nextStatus)) {
            shareMapper.updateStatusByIdAndCurrentStatus(share.getId(), share.getStatus(), nextStatus);
            share.setStatus(nextStatus);
        }
        return share;
    }

    public int calculateShareStatus(Share share) {
        if (Objects.equals(share.getStatus(), ShareStatus.CANCELED)) {
            return ShareStatus.CANCELED;
        }
        if (share.getExpireTime() != null && share.getExpireTime().isBefore(LocalDateTime.now())) {
            return ShareStatus.EXPIRED;
        }
        if (share.getMaxAccessCount() != null && defaultZero(share.getCurrentAccessCount()) >= share.getMaxAccessCount()) {
            return ShareStatus.ACCESS_LIMIT_REACHED;
        }
        return ShareStatus.NORMAL;
    }

    public BusinessException invalidShareException(Integer status) {
        return switch (status) {
            case ShareStatus.CANCELED -> new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "分享已取消",
                    ShareStatusMeta.toData(ShareStatus.CANCELED));
            case ShareStatus.EXPIRED -> new BusinessException(ErrorCode.SHARE_EXPIRED, "分享已过期",
                    ShareStatusMeta.toData(ShareStatus.EXPIRED));
            case ShareStatus.ACCESS_LIMIT_REACHED -> new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "分享访问次数已用尽",
                    ShareStatusMeta.toData(ShareStatus.ACCESS_LIMIT_REACHED));
            default -> new BusinessException(ErrorCode.SHARE_NOT_FOUND, "分享不存在",
                    ShareStatusMeta.toData(ShareStatus.CANCELED));
        };
    }

    public int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }
}
