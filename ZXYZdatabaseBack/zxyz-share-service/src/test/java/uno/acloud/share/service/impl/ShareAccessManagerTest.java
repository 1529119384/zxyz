package uno.acloud.share.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import uno.acloud.common.ErrorCode;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.common.ShareStatus;
import uno.acloud.share.config.ShareProperties;
import uno.acloud.share.controller.support.ShareCookieManager;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.infrastructure.entity.Share;
import uno.acloud.share.infrastructure.mapper.ShareMapper;
import uno.acloud.share.service.model.ShareVerifyResult;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertEquals(ErrorCode.SHARE_NOT_FOUND, ex.getErrorCode());
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
                .thenReturn(new BusinessException(ErrorCode.SHARE_EXPIRED, "分享已过期"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(ErrorCode.SHARE_EXPIRED, ex.getErrorCode());
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
        assertEquals(ErrorCode.SHARE_PASSWORD_INVALID, ex.getErrorCode());
    }

    @Test
    void verifyShare_throwsWhenAccessQuotaExhausted() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("abc123");
        Share share = createNormalShare();
        share.setMaxAccessCount(10);
        share.setCurrentAccessCount(10);
        when(shareMapper.getByShareKey("abc123")).thenReturn(share);
        when(shareStatusCalculator.refreshStatusIfNeeded(share)).thenReturn(share);
        when(shareMapper.tryIncrementAccessCountWhenUnderLimit(1L)).thenReturn(0);
        // After quota exhausted, refreshStatusIfNeeded is called again; status stays NORMAL
        // so invalidShareException is called with the current share status (NORMAL=0)
        when(shareStatusCalculator.invalidShareException(ShareStatus.NORMAL))
                .thenReturn(new BusinessException(ErrorCode.SHARE_STATUS_INVALID, "分享访问次数已用尽"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareAccessManager.verifyShare(request, null));
        assertEquals(ErrorCode.SHARE_STATUS_INVALID, ex.getErrorCode());
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
        when(shareMapper.tryIncrementAccessCountWhenUnderLimit(1L)).thenReturn(1);
        when(shareStatusCalculator.defaultZero(5)).thenReturn(5);

        ShareVerifyResult result = shareAccessManager.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertEquals("some-token", result.getAccessToken());
        assertEquals(share.getExpireTime(), result.getExpireTime());
        verify(shareMapper).tryIncrementAccessCountWhenUnderLimit(1L);
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
        when(shareMapper.tryIncrementAccessCountWhenUnderLimit(1L)).thenReturn(1);
        when(shareStatusCalculator.defaultZero(0)).thenReturn(0);
        when(shareProperties.getCookieSecret()).thenReturn("test-secret");
        when(shareCookieManager.buildAccessToken(share, "test-secret")).thenReturn("token-val");

        ShareVerifyResult result = shareAccessManager.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertEquals("token-val", result.getAccessToken());
        verify(passwordEncoder, never()).matches(any(), any());
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
        assertEquals(ErrorCode.SHARE_STATUS_INVALID, ex.getErrorCode());
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
