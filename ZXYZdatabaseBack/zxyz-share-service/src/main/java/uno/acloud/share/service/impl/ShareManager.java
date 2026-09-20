package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import uno.acloud.common.util.TransactionHelper;
import uno.acloud.common.ErrorCode;
import uno.acloud.common.PageResult;
import uno.acloud.common.ShareErrorCode;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.config.ShareTimeSource;
import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.share.infrastructure.client.model.ShareFileProjection;
import uno.acloud.vo.InternalUserInfoVO;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.entity.ShareItem;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.vo.ShareCreateResponse;
import uno.acloud.share.vo.ShareMyListItemVO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ShareManager {

    private static final String SHARE_PATH_PREFIX = "/s/";

    /**
     * 我的分享列表默认每页条数：与 ShareController#getMyShares 的 defaultValue = "10"
     * 保持一致（PageResult.DEFAULT_PAGE_SIZE 是 20，两者语义不同，不能互相顶替）。
     */
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ShareMapper shareMapper;
    private final ShareValidator shareValidator;
    private final ShareInputNormalizer shareInputNormalizer;
    private final ShareViewMapper shareViewMapper;
    private final ShareStatusCalculator shareStatusCalculator;
    private final ShareProperties shareProperties;
    private final PasswordEncoder passwordEncoder;
    private final TransactionHelper transactionHelper;
    /** 时间基准（审计 D3）：写入端与 ShareStatusCalculator / ShareCookieManager 共用同一口井。 */
    private final ShareTimeSource timeSource;

    public ShareManager(ShareMapper shareMapper,
                              ShareValidator shareValidator,
                              ShareInputNormalizer shareInputNormalizer,
                              ShareViewMapper shareViewMapper,
                              ShareStatusCalculator shareStatusCalculator,
                              ShareProperties shareProperties,
                              PasswordEncoder passwordEncoder,
                              TransactionHelper transactionHelper,
                              ShareTimeSource timeSource) {
        this.shareMapper = shareMapper;
        this.shareValidator = shareValidator;
        this.shareInputNormalizer = shareInputNormalizer;
        this.shareViewMapper = shareViewMapper;
        this.shareStatusCalculator = shareStatusCalculator;
        this.shareProperties = shareProperties;
        this.passwordEncoder = passwordEncoder;
        this.transactionHelper = transactionHelper;
        this.timeSource = timeSource;
    }

    public ShareCreateResponse createShare(ShareCreateRequest request, Long userId) {
        shareValidator.validateUserId(userId);
        if (request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分享参数不能为空");
        }
        List<Long> normalizedFileIds = shareInputNormalizer.normalizeFileIds(request.getFileIds());
        Map<Long, ShareFileProjection> fileInfoMap = shareValidator.requireActiveFiles(normalizedFileIds);
        shareValidator.requireShareFileAccess(normalizedFileIds, userId);
        InternalUserInfoVO user = shareValidator.requireUser(userId);
        return transactionHelper.execute(status -> {
            boolean needPassword = resolveNeedPassword(request);
            String normalizedPassword = needPassword ? shareInputNormalizer.normalizePassword(request.getPassword()) : null;
            LocalDateTime now = timeSource.now();

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
                ShareFileProjection fileInfo = fileInfoMap.get(fileId);
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

    public PageResult<ShareMyListItemVO> getMyShares(Long userId, Integer page, Integer pageSize) {
        shareValidator.validateUserId(userId);
        int safePage = PageResult.normalizePage(page);
        int safePageSize = normalizeSharePageSize(pageSize);
        int total = shareMapper.countByUserId(userId);
        if (total <= 0) {
            return PageResult.of(safePage, safePageSize, 0, List.<ShareMyListItemVO>of());
        }

        int offset = PageResult.offsetOf(safePage, safePageSize);
        if (offset >= total) {
            return PageResult.of(safePage, safePageSize, total, List.<ShareMyListItemVO>of());
        }

        List<Share> shares = shareMapper.listPageByUserId(userId, offset, safePageSize);
        shareStatusCalculator.batchRefreshStatusIfNeeded(shares);
        List<ShareMyListItemVO> rows = shares.stream()
                .map(this::toShareMyListItemVO)
                .collect(Collectors.toList());
        return PageResult.of(safePage, safePageSize, total, rows);
    }

    /**
     * 我的分享列表的 pageSize 归一化。
     *
     * <p><b>为什么不直接用 PageResult.normalizePageSize</b>：它把「未指定」落成
     * PageResult.DEFAULT_PAGE_SIZE（20），而本接口的历史默认值是 10
     * （见 ShareController#getMyShares 的 defaultValue）。直接复用会把每页条数从 10
     * 悄悄改成 20，属于契约外变更。</p>
     *
     * <p><b>上限是本接口原先缺的闸门</b>：旧实现只判 pageSize &lt; 1，调用方传一个极大的
     * pageSize 就等于把分页接口恢复成「全表查询」（与 07-P0-2 里 admin 侧修的同一类问题）。
     * 这里统一钳制到 PageResult.MAX_PAGE_SIZE。</p>
     */
    private static int normalizeSharePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, PageResult.MAX_PAGE_SIZE);
    }

    public ShareMyListItemVO getShareDetail(Long shareId, Long userId) {
        shareValidator.validateUserId(userId);
        if (shareId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "shareId 不能为空");
        }
        Share share = shareMapper.getByIdAndUserId(shareId, userId);
        if (share == null) {
            throw new BusinessException(ShareErrorCode.SHARE_NOT_FOUND.getCode(), "分享不存在");
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
            throw new BusinessException(ShareErrorCode.SHARE_NOT_FOUND.getCode(), "分享不存在");
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
        // 安全修复：不再将密码嵌入 URL 查询参数（避免密码出现在浏览器历史、日志、Referer 头中）。
        // 密码通过 buildShareMessage() 在复制文本中单独展示。
        return StringUtils.removeEnd(shareProperties.getFrontendBaseUrl(), "/") + SHARE_PATH_PREFIX + shareKey;
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
