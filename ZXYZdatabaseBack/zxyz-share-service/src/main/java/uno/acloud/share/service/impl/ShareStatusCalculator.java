package uno.acloud.share.service.impl;

import org.springframework.stereotype.Component;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ShareErrorCode;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.common.ShareStatusMeta;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;

import java.util.List;
import java.util.Objects;

@Component
public class ShareStatusCalculator {

    private final ShareMapper shareMapper;
    /** 时间基准与写入端（ShareManager）同源，见 ShareTimeSource（审计 D3）。 */
    private final ShareTimeSource timeSource;

    public ShareStatusCalculator(ShareMapper shareMapper, ShareTimeSource timeSource) {
        this.shareMapper = shareMapper;
        this.timeSource = timeSource;
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
        if (share.getExpireTime() != null && share.getExpireTime().isBefore(timeSource.now())) {
            return ShareStatus.EXPIRED;
        }
        if (share.getMaxAccessCount() != null && defaultZero(share.getCurrentAccessCount()) >= share.getMaxAccessCount()) {
            return ShareStatus.ACCESS_LIMIT_REACHED;
        }
        return ShareStatus.NORMAL;
    }

    public BusinessException invalidShareException(Integer status) {
        return switch (status) {
            case ShareStatus.CANCELED -> new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "分享已取消",
                    ShareStatusMeta.toData(ShareStatus.CANCELED));
            case ShareStatus.EXPIRED -> new BusinessException(ShareErrorCode.SHARE_EXPIRED.getCode(), "分享已过期",
                    ShareStatusMeta.toData(ShareStatus.EXPIRED));
            case ShareStatus.ACCESS_LIMIT_REACHED -> new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "分享访问次数已用尽",
                    ShareStatusMeta.toData(ShareStatus.ACCESS_LIMIT_REACHED));
            default -> new BusinessException(ShareErrorCode.SHARE_NOT_FOUND.getCode(), "分享不存在",
                    ShareStatusMeta.toData(ShareStatus.CANCELED));
        };
    }

    public int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 批量刷新状态：先计算所有状态，再批量更新需要变更的记录，避免 N+1 写入。
     */
    public List<Share> batchRefreshStatusIfNeeded(List<Share> shares) {
        for (Share share : shares) {
            int nextStatus = calculateShareStatus(share);
            if (!Objects.equals(share.getStatus(), nextStatus)) {
                shareMapper.updateStatusByIdAndCurrentStatus(share.getId(), share.getStatus(), nextStatus);
                share.setStatus(nextStatus);
            }
        }
        return shares;
    }
}
