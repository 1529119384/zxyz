package uno.acloud.share.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
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
import java.time.Duration;
import java.util.Objects;

@Component
public class ShareAccessManager {

    /** 每次访问配额扣减的相同访问令牌去重 key 前缀 */
    private static final String BURN_KEY_PREFIX = "zxyz:share:burn:";
    /** 同一天内同一访问令牌只扣减一次配额的去重窗口 */
    private static final Duration BURN_DEDUP_TTL = Duration.ofHours(24);

    private final ShareMapper shareMapper;
    private final ShareStatusCalculator shareStatusCalculator;
    private final ShareProperties shareProperties;
    private final ShareCookieManager shareCookieManager;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate stringRedisTemplate;

    public ShareAccessManager(ShareMapper shareMapper,
                              ShareStatusCalculator shareStatusCalculator,
                              ShareProperties shareProperties,
                              ShareCookieManager shareCookieManager,
                              PasswordEncoder passwordEncoder,
                              StringRedisTemplate stringRedisTemplate) {
        this.shareMapper = shareMapper;
        this.shareStatusCalculator = shareStatusCalculator;
        this.shareProperties = shareProperties;
        this.shareCookieManager = shareCookieManager;
        this.passwordEncoder = passwordEncoder;
        this.stringRedisTemplate = stringRedisTemplate;
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
        // 校验通过即发放访问令牌，不在此处扣减访问配额（配额只在真正访问内容时扣减，见 consumeAccessQuota）
        return ShareVerifyResult.passedWithToken(shareCookieManager.buildAccessToken(share, shareProperties.getCookieSecret()), share.getExpireTime());
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

    /**
     * 在内容访问路径上扣减访问配额（如获取文件列表、下载、流式下载）。
     * <p>
     * 只有当 share 处于限制内才原子扣减（{@code tryIncrementAccessCountWhenUnderLimit}）；
     * 配额已耗尽时抛出分享失效异常。相同访问令牌在 24 小时内（按 token hash 去重）只扣减一次，
     * 避免同一访客重复拉取列表/文件把配额逐次烧掉。
     *
     * @param share            已通过校验的分享
     * @param shareAccessToken 当前的访问令牌（可为空；为空时不参与去重，每次访问都扣减）
     */
    public void consumeAccessQuota(Share share, String shareAccessToken) {
        String tokenHash = hashAccessToken(shareAccessToken);
        // 同一天内同一访问令牌已扣减过则跳过（去重），否则以原子写的方式占位并扣减
        if (StringUtils.isNotBlank(tokenHash)
                && !markBurnInProgress(share.getId(), tokenHash)) {
            return;
        }
        int affectedRows;
        if (share.getMaxAccessCount() == null) {
            affectedRows = shareMapper.incrementAccessCount(share.getId());
        } else {
            affectedRows = shareMapper.tryIncrementAccessCountWhenUnderLimit(share.getId());
        }
        if (affectedRows != 1) {
            if (share.getMaxAccessCount() != null) {
                share.setCurrentAccessCount(share.getMaxAccessCount());
                throw shareStatusCalculator.invalidShareException(ShareStatus.ACCESS_LIMIT_REACHED);
            }
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分享访问计数失败");
        }
        share.setCurrentAccessCount(shareStatusCalculator.defaultZero(share.getCurrentAccessCount()) + 1);
    }

    /**
     * 以原子 SET-and-EXPIRE 占位标记「该访问令牌当日已扣减」。
     * 返回 {@code true} 表示本次为该令牌当日首次访问（应扣减）;返回 {@code false} 表示当日已访问过（去重，跳过扣减）。
     */
    private boolean markBurnInProgress(Long shareId, String tokenHash) {
        String key = BURN_KEY_PREFIX + shareId + ":" + tokenHash;
        Boolean firstTime = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", BURN_DEDUP_TTL);
        return Boolean.TRUE.equals(firstTime);
    }

    /**
     * 计算访问令牌的稳定哈希，用于构建幂等/去重 key。
     * 令牌为 HMAC 十六进制串，取 SHA-256 前 16 个十六进制字符，避免 Redis key 过长。
     */
    private String hashAccessToken(String shareAccessToken) {
        if (StringUtils.isBlank(shareAccessToken)) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(shareAccessToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("访问令牌哈希计算失败", e);
        }
    }

}
