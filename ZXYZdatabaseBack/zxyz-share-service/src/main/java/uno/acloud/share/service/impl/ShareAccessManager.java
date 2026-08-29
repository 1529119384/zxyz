package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.ShareErrorCode;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.common.ShareStatusMeta;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.controller.support.ShareCookieManager;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.service.model.ShareVerifyResult;
import uno.acloud.share.vo.SharePublicInfoVO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

@Component
public class ShareAccessManager {

    private final ShareMapper shareMapper;
    private final ShareStatusCalculator shareStatusCalculator;
    private final ShareProperties shareProperties;
    private final ShareCookieManager shareCookieManager;
    private final PasswordEncoder passwordEncoder;

    public ShareAccessManager(ShareMapper shareMapper,
                              ShareStatusCalculator shareStatusCalculator,
                              ShareProperties shareProperties,
                              ShareCookieManager shareCookieManager,
                              PasswordEncoder passwordEncoder) {
        this.shareMapper = shareMapper;
        this.shareStatusCalculator = shareStatusCalculator;
        this.shareProperties = shareProperties;
        this.shareCookieManager = shareCookieManager;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(rollbackFor = Exception.class)
    public ShareVerifyResult verifyShare(ShareVerifyRequest request, String shareAccessToken) {
        if (request == null || StringUtils.isBlank(request.getShareKey())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shareKey 不能为空");
        }
        Share share = requireAvailableShare(request.getShareKey());
        if (hasValidAccessToken(share, shareAccessToken)) {
            return ShareVerifyResult.passedWithoutNewToken();
        }
        validateSharePassword(share, request.getPassword());
        if (!tryConsumeAccessQuota(share)) {
            Share refreshedShare = shareStatusCalculator.refreshStatusIfNeeded(share);
            throw shareStatusCalculator.invalidShareException(refreshedShare.getStatus());
        }
        Share refreshedShare = shareStatusCalculator.refreshStatusIfNeeded(share);
        if (!Objects.equals(refreshedShare.getStatus(), ShareStatus.NORMAL)) {
            throw shareStatusCalculator.invalidShareException(refreshedShare.getStatus());
        }
        return ShareVerifyResult.passedWithToken(shareCookieManager.buildAccessToken(refreshedShare, shareProperties.getCookieSecret()), refreshedShare.getExpireTime());
    }

    public SharePublicInfoVO getPublicShareInfo(String shareKey, String shareAccessToken) {
        Share share = shareStatusCalculator.refreshStatusIfNeeded(requireShare(shareKey));
        if (!Objects.equals(share.getStatus(), ShareStatus.NORMAL)) {
            throw shareStatusCalculator.invalidShareException(share.getStatus());
        }
        boolean needPassword = StringUtils.isNotBlank(share.getPassword());
        boolean passed = !needPassword || hasValidAccessToken(share, shareAccessToken);
        return new SharePublicInfoVO(
                share.getShareKey(),
                null,
                "分享",
                needPassword,
                passed,
                passed,
                share.getStatus(),
                ShareStatusMeta.textOf(share.getStatus())
        );
    }

    public Share requireAccessibleShare(String shareKey, String shareAccessToken) {
        Share share = requireAvailableShare(shareKey);
        if (StringUtils.isNotBlank(share.getPassword()) && !hasValidAccessToken(share, shareAccessToken)) {
            throw new BusinessException(ShareErrorCode.SHARE_STATUS_INVALID.getCode(), "请先通过分享校验");
        }
        return share;
    }

    private Share requireAvailableShare(String shareKey) {
        Share share = shareStatusCalculator.refreshStatusIfNeeded(requireShare(shareKey));
        if (!Objects.equals(share.getStatus(), ShareStatus.NORMAL)) {
            throw shareStatusCalculator.invalidShareException(share.getStatus());
        }
        return share;
    }

    private Share requireShare(String shareKey) {
        if (StringUtils.isBlank(shareKey)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shareKey 不能为空");
        }
        Share share = shareMapper.getByShareKey(shareKey.trim());
        if (share == null) {
            throw new BusinessException(ShareErrorCode.SHARE_NOT_FOUND.getCode(), "分享不存在");
        }
        return share;
    }

    public boolean hasValidAccessToken(Share share, String shareAccessToken) {
        if (share == null || StringUtils.isBlank(shareAccessToken)) {
            return false;
        }
        byte[] expected = shareCookieManager.buildAccessToken(share, shareProperties.getCookieSecret()).getBytes(StandardCharsets.UTF_8);
        byte[] actual = shareAccessToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private void validateSharePassword(Share share, String rawPassword) {
        if (StringUtils.isBlank(share.getPassword())) {
            return;
        }
        if (!passwordEncoder.matches(StringUtils.trimToEmpty(rawPassword), share.getPassword())) {
            throw new BusinessException(ShareErrorCode.SHARE_PASSWORD_INVALID.getCode(), "提取码错误");
        }
    }

    private boolean tryConsumeAccessQuota(Share share) {
        int affectedRows;
        if (share.getMaxAccessCount() == null) {
            affectedRows = shareMapper.incrementAccessCount(share.getId());
        } else {
            affectedRows = shareMapper.tryIncrementAccessCountWhenUnderLimit(share.getId());
        }
        if (affectedRows != 1) {
            if (share.getMaxAccessCount() != null) {
                share.setCurrentAccessCount(share.getMaxAccessCount());
                return false;
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分享访问计数失败");
        }
        share.setCurrentAccessCount(shareStatusCalculator.defaultZero(share.getCurrentAccessCount()) + 1);
        return true;
    }

}
