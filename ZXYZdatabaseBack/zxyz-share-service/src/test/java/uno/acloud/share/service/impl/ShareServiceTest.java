package uno.acloud.share.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static uno.acloud.common.ShareErrorCode.*;
import uno.acloud.exception.BusinessException;
import uno.acloud.share.dto.ShareCreateRequest;
import uno.acloud.share.dto.ShareVerifyRequest;
import uno.acloud.share.service.model.ShareVerifyResult;
import uno.acloud.share.vo.ShareCreateResponse;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

    @Mock
    private ShareManager shareManageService;

    @Mock
    private ShareAccessManager shareAccessService;

    @Mock
    private ShareContentProvider shareContentService;

    private ShareService shareService;

    @BeforeEach
    void setUp() {
        shareService = new ShareService(shareManageService, shareAccessService, shareContentService);
    }

    // ==================== Create share link — should succeed ====================

    @Test
    void createShare_validRequest_shouldSucceed() {
        Long userId = 1L;
        ShareCreateRequest request = new ShareCreateRequest();
        request.setFileIds(List.of(100L, 200L));
        request.setNeedPassword(true);
        request.setPassword("abc123");

        ShareCreateResponse expectedResponse = new ShareCreateResponse(
                1L, "share-key-uuid", "abc123",
                "http://localhost:5173/s/share-key-uuid?psw=abc123",
                LocalDateTime.now().plusDays(7), 0
        );
        when(shareManageService.createShare(request, userId)).thenReturn(expectedResponse);

        ShareCreateResponse result = shareService.createShare(request, userId);

        assertNotNull(result);
        assertEquals(1L, result.getShareId());
        assertEquals("share-key-uuid", result.getShareKey());
        assertEquals("abc123", result.getPassword());
        verify(shareManageService).createShare(request, userId);
    }

    // ==================== Access share with correct password — should succeed ====================

    @Test
    void verifyShare_correctPassword_shouldSucceed() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("share-key-uuid");
        request.setPassword("correct-password");

        ShareVerifyResult expectedResult = ShareVerifyResult.passedWithToken(
                "access-token-value", LocalDateTime.now().plusHours(1));
        when(shareAccessService.verifyShare(request, null)).thenReturn(expectedResult);

        ShareVerifyResult result = shareService.verifyShare(request, null);

        assertNotNull(result);
        assertTrue(result.getResponse().getPassed());
        assertNotNull(result.getAccessToken());
        verify(shareAccessService).verifyShare(request, null);
    }

    // ==================== Access share with wrong password — should throw ====================

    @Test
    void verifyShare_wrongPassword_shouldThrow() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("share-key-uuid");
        request.setPassword("wrong-password");

        when(shareAccessService.verifyShare(request, null))
                .thenThrow(new BusinessException(SHARE_PASSWORD_INVALID.getCode(), "提取码错误"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.verifyShare(request, null));
        assertEquals(SHARE_PASSWORD_INVALID.getCode(), ex.getErrorCode());
        assertTrue(ex.getMessage().contains("提取码错误"));
    }

    // ==================== Access expired share — should throw ====================

    @Test
    void verifyShare_expiredShare_shouldThrow() {
        ShareVerifyRequest request = new ShareVerifyRequest();
        request.setShareKey("expired-share-key");
        request.setPassword("any-password");

        when(shareAccessService.verifyShare(request, null))
                .thenThrow(new BusinessException(SHARE_EXPIRED.getCode(), "分享已过期"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> shareService.verifyShare(request, null));
        assertEquals(SHARE_EXPIRED.getCode(), ex.getErrorCode());
    }
}
