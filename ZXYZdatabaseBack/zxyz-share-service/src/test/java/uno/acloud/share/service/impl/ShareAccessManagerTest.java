package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import static uno.acloud.common.ShareErrorCode.*;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.controller.support.ShareCookieManager;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.service.model.ShareVerifyResult;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uno.acloud.common.ErrorCode.BAD_REQUEST;
import static uno.acloud.common.ErrorCode.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
class ShareAccessManagerTest {

    @Mock
    private ShareMapper shareMapper;
    @Mock
    private ShareStatusCalculator shareStatusCalculator;
    @Mock
    private ShareProperties shareProperties;
    @Mock
    private ShareCookieManager shareCookieManager;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ShareAccessManager shareAccessManager;

    private Share createNormalShare() {
        Share share = new Share();
        share.setId(1L);
        share.setShareKey("abc123");
        share.setUserId(100L);
        share.setUsername("testuser");
        share.setPassword(null);
        share.setExpireTime(LocalDateTime.now().plusDays(1));
        share.setMaxAccessCount(null);
        share.setCurrentAccessCount(0);
        share.setStatus(ShareStatus.NORMAL);
        share.setCreateTime(LocalDateTime.now());
        return share;
    }

    // --- verifyShare tests ---

    @Test
    void verifyShare_throwsWhenRequestNull() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(null, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void verifyShare_throwsWhenShareKeyBlank() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("  ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode());
    }

    @Test
    void verifyShare_throwsWhenShareNotFound() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("nonexistent");
        when(shareMapper.getByShareKey("nonexistent")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(SHARE_NOT_FOUND.getCode(), ex.getErrorCode());
    }

    @Test
    void verifyShare_throwsWhenShareNotNormal() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        share.setStatus(ShareStatus.EXPIRED);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareStatusCalculator.invalidShareException(ShareStatus.EXPIRED))
                .thenReturn(new BusinessException(SHARE_EXPIRED.getCode(), "分享已过期"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(SHARE_EXPIRED.getCode(), ex.getErrorCode());
    }

    @Test
    void verifyShare_passesWithExistingValidToken() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("valid-token");

        ShareVerifyResult result = shareAccessManager.verifyShare(request, "valid-token");

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertNull(result.getAccessToken());
        verify(shareMapper, never()).incrementAccessCount(any());
        verify(shareMapper, never()).tryIncrementAccessCountWhenUnderLimit(any());
    }

    @Test
    void verifyShare_throwsWhenPasswordWrong() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        request.setPassword("wrong");
        Share share = createNormalShare();
        share.setPassword("$2a$10$hashedpassword");
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(passwordEncoder.matches("wrong", "$2a$10$hashedpassword")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(SHARE_PASSWORD_INVALID.getCode(), ex.getErrorCode());
    }

    @Test
    void verifyShare_doesNotBurnQuota() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        share.setMaxAccessCount(10);
        share.setCurrentAccessCount(10);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("some-token");

        ShareVerifyResult result = shareAccessManager.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        // verify 只发令牌、不烧配额（即使配额已用尽），配额扣减改在内容访问路径
        assertEquals("some-token", result.getAccessToken());
        verify(shareMapper, never()).incrementAccessCount(anyLong());
        verify(shareMapper, never()).tryIncrementAccessCountWhenUnderLimit(anyLong());
    }

    @Test
    void verifyShare_succeedsAndReturnsNewToken() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        share.setMaxAccessCount(100);
        share.setCurrentAccessCount(5);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("some-token");

        ShareVerifyResult result = shareAccessManager.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertEquals("some-token", result.getAccessToken());
        assertEquals(share.getExpireTime(), result.getExpireTime());
        verify(shareMapper, never()).tryIncrementAccessCountWhenUnderLimit(anyLong());
    }

    @Test
    void verifyShare_passwordNotRequiredWhenBlank() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        share.setPassword(null);
        share.setMaxAccessCount(100);
        share.setCurrentAccessCount(0);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("token-val");

        ShareVerifyResult result = shareAccessManager.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertEquals("token-val", result.getAccessToken());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    // --- consumeAccessQuota tests ---

    @Test
    void consumeAccessQuota_burnsOncePerTokenWithinDay_dedup() {
        Share share = createNormalShare();
        share.setMaxAccessCount(100);
        share.setCurrentAccessCount(5);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 第一次：令牌当日首次访问 → 应扣减
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shareMapper.tryIncrementAccessCountWhenUnderLimit(1L)).thenReturn(1);
        when(shareStatusCalculator.defaultZero(5)).thenReturn(5);

        shareAccessManager.consumeAccessQuota(share, "some-access-token");

        verify(shareMapper).tryIncrementAccessCountWhenUnderLimit(1L);
        assertEquals(6, share.getCurrentAccessCount());
    }

    @Test
    void consumeAccessQuota_skipsWhenAlreadyBurnedToday() {
        Share share = createNormalShare();
        share.setMaxAccessCount(100);
        share.setCurrentAccessCount(5);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 令牌当日已出现过 → 去重，不扣减
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        shareAccessManager.consumeAccessQuota(share, "some-access-token");

        verify(shareMapper, never()).incrementAccessCount(anyLong());
        verify(shareMapper, never()).tryIncrementAccessCountWhenUnderLimit(anyLong());
        assertEquals(5, share.getCurrentAccessCount());
    }

    @Test
    void consumeAccessQuota_throwsWhenQuotaExhausted() {
        Share share = createNormalShare();
        share.setMaxAccessCount(10);
        share.setCurrentAccessCount(10);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(shareMapper.tryIncrementAccessCountWhenUnderLimit(1L)).thenReturn(0);
        when(shareStatusCalculator.invalidShareException(ShareStatus.ACCESS_LIMIT_REACHED))
                .thenReturn(new BusinessException(SHARE_STATUS_INVALID.getCode(), "分享访问次数已用尽"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.consumeAccessQuota(share, "some-access-token"));

        assertEquals(SHARE_STATUS_INVALID.getCode(), ex.getErrorCode());
        assertEquals(10, share.getCurrentAccessCount());
    }

    // --- requireAccessibleShare tests ---

    @Test
    void requireAccessibleShare_throwsWhenPasswordProtectedAndNoToken() {
        Share share = createNormalShare();
        share.setPassword("$2a$10$hashed");
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.requireAccessibleShare("abc123", null));
        assertEquals(SHARE_STATUS_INVALID.getCode(), ex.getErrorCode());
    }

    @Test
    void requireAccessibleShare_passesWhenNoPassword() {
        Share share = createNormalShare();
        share.setPassword(null);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);

        Share result = shareAccessManager.requireAccessibleShare("abc123", null);

        assertNotNull(result);
        assertEquals("abc123", result.getShareKey());
    }

    @Test
    void requireAccessibleShare_passesWhenHasValidToken() {
        Share share = createNormalShare();
        share.setPassword("$2a$10$hashed");
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("valid-token");

        Share result = shareAccessManager.requireAccessibleShare("abc123", "valid-token");

        assertNotNull(result);
        assertEquals("abc123", result.getShareKey());
    }
}
