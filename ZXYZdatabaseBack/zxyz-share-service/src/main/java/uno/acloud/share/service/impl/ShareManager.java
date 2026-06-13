package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.common.ErrorCode;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.dto.FileInfoDTO;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.vo.InternalUserInfoVO;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.vo.ShareCreateResponse;
import uno.acloud.share.vo.ShareMyListItemVO;
import uno.acloud.share.vo.ShareMyListResponseVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ShareManager {

    private static final String SHARE_PATH_PREFIX = "/s/";

    private final ShareMapper shareMapper;
    private final ShareValidator shareValidator;
    private final ShareInputNormalizer shareInputNormalizer;
    private final ShareViewMapper shareViewMapper;
    private final ShareStatusCalculator shareStatusCalculator;
    private final ShareProperties shareProperties;
    private final PasswordEncoder passwordEncoder;
    private final TransactionHelper transactionHelper;

    public ShareManager(ShareMapper shareMapper,
                              ShareValidator shareValidator,
                              ShareInputNormalizer shareInputNormalizer,
                              ShareViewMapper shareViewMapper,
                              ShareStatusCalculator shareStatusCalculator,
                              ShareProperties shareProperties,
                              PasswordEncoder passwordEncoder,
                              TransactionHelper transactionHelper) {
        this.shareMapper = shareMapper;
        this.shareValidator = shareValidator;
        this.shareInputNormalizer = shareInputNormalizer;
        this.shareViewMapper = shareViewMapper;
        this.shareStatusCalculator = shareStatusCalculator;
        this.shareProperties = shareProperties;
        this.passwordEncoder = passwordEncoder;
        this.transactionHelper = transactionHelper;
    }

    public ShareCreateResponse createShare(ShareCreateRequest request, Long userId) {
        shareValidator.validateUserId(userId);
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分享参数不能为空");
        }
        List<Long> normalizedFileIds = shareInputNormalizer.normalizeFileIds(request.getFileIds());
        Map<Long, FileInfoDTO> fileInfoMap = shareValidator.requireActiveFiles(normalizedFileIds);
        InternalUserInfoVO user = shareValidator.requireUser(userId);
        return transactionHelper.execute(status -> {
            boolean needPassword = resolveNeedPassword(request);
            String normalizedPassword = needPassword ? shareInputNormalizer.normalizePassword(request.getPassword()) : null;
            LocalDateTime now = LocalDateTime.now();

            Share share = new Share();
            share.setShareKey(UUID.randomUUID().toString());
            share.setUserId(userId);
            share.setUsername(user.getUsername());
            share.setPassword(normalizedPassword != null ? passwordEncoder.encode(normalizedPassword) : null);
            share.setExpireTime(shareInputNormalizer.resolveExpireTime(request.getExpireType(), now));
            share.setMaxAccessCount(shareInputNormalizer.resolveMaxAccessCount(request.getMaxAccessCount()));
            share.setCurrentAccessCount(0);
            share.setStatus(ShareStatus.NORMAL);
            share.setCreateTime(now);
            if (shareMapper.insert(share) != 1 || share.getId() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "创建分享失败");
            }

            List<ShareItem> shareItems = new ArrayList<>();
            for (Long fileId : normalizedFileIds) {
                FileInfoDTO fileInfo = fileInfoMap.get(fileId);
                shareItems.add(new ShareItem(null, share.getId(), fileId, fileInfo.getFileType(), now));
            }
            if (!shareItems.isEmpty()) {
                shareMapper.batchInsertShareItems(shareItems);
            }

            String shareUrl = buildShareUrl(share.getShareKey(), normalizedPassword, resolveAutoFillPassword(request));
            return new ShareCreateResponse(
                    share.getId(),
                    share.getShareKey(),
                    normalizedPassword,
                    shareUrl,
                    share.getExpireTime(),
                    share.getMaxAccessCount() == null ? 0 : share.getMaxAccessCount()
            );
        });
    }

    public ShareMyListResponseVO getMyShares(Long userId, Integer page, Integer pageSize) {
        shareValidator.validateUserId(userId);
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : pageSize;
        int total = shareMapper.countByUserId(userId);
        if (total <= 0) {
            return new ShareMyListResponseVO(0, List.<ShareMyListItemVO>of());
        }

        int offset = (safePage - 1) * safePageSize;
        if (offset >= total) {
            return new ShareMyListResponseVO(total, List.<ShareMyListItemVO>of());
        }

        List<ShareMyListItemVO> rows = shareMapper.listPageByUserId(userId, offset, safePageSize).stream()
                .map(shareStatusCalculator::refreshStatusIfNeeded)
                .map(this::toShareMyListItemVO)
                .collect(Collectors.toList());
        return new ShareMyListResponseVO(total, rows);
    }

    public ShareMyListItemVO getShareDetail(Long shareId, Long userId) {
        shareValidator.validateUserId(userId);
        if (shareId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shareId 不能为空");
        }
        Share share = shareMapper.getByIdAndUserId(shareId, userId);
        if (share == null) {
            throw new BusinessException(ErrorCode.SHARE_NOT_FOUND, "分享不存在");
        }
        share = shareStatusCalculator.refreshStatusIfNeeded(share);
        return toShareMyListItemVO(share);
    }

    public void cancelShare(Long shareId, Long userId) {
        shareValidator.validateUserId(userId);
        if (shareId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shareId 不能为空");
        }
        if (shareMapper.getByIdAndUserId(shareId, userId) == null) {
            throw new BusinessException(ErrorCode.SHARE_NOT_FOUND, "分享不存在");
        }
        shareMapper.updateStatusByIdAndUserId(shareId, userId, ShareStatus.CANCELED);
    }

    public ShareMyListItemVO updateShareStatus(Long shareId, Integer status, Long userId) {
        shareValidator.validateUserId(userId);
        if (status == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "status 不能为空");
        }
        if (!Integer.valueOf(ShareStatus.CANCELED).equals(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前仅支持取消分享");
        }
        cancelShare(shareId, userId);
        return getShareDetail(shareId, userId);
    }

    private ShareMyListItemVO toShareMyListItemVO(Share share) {
        // Password is stored as BCrypt hash; cannot be used to auto-fill URL.
        // The creator already received the raw password in the creation response.
        String shareUrl = buildShareUrl(share.getShareKey(), null, false);
        return shareViewMapper.toShareMyListItemVO(share, shareUrl);
    }

    public String buildShareUrl(String shareKey, String password, boolean autoFillPassword) {
        String baseUrl = StringUtils.removeEnd(shareProperties.getFrontendBaseUrl(), "/") + SHARE_PATH_PREFIX + shareKey;
        if (autoFillPassword && StringUtils.isNotBlank(password)) {
            return baseUrl + "?psw=" + password;
        }
        return baseUrl;
    }

    private boolean resolveNeedPassword(ShareCreateRequest request) {
        return Boolean.TRUE.equals(request.getNeedPassword());
    }

    private boolean resolveAutoFillPassword(ShareCreateRequest request) {
        return Boolean.TRUE.equals(request.getAutoFillPassword());
    }

    public int cleanupShareItemsByFileIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return 0;
        }
        return shareMapper.deleteShareItemsByFileIds(fileIds);
    }
}
