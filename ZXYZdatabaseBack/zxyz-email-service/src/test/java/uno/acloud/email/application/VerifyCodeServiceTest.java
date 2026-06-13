package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uno.acloud.common.ErrorCode;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.domain.VerifyCode;
import uno.acloud.email.infrastructure.VerifyCodeMapper;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VerifyCodeServiceTest {

    @Mock
    private VerifyCodeMapper verifyCodeMapper;
    @Mock
    private EmailDispatchService emailDispatchService;
    @Mock
    private EmailRateLimiter emailRateLimiter;
    @Mock
    private EmailSendingAvailabilityService emailSendingAvailabilityService;

    @Test
    void sendCodeShouldCreateVerifyCodeAndEmailRecord() {
        EmailProperties properties = new EmailProperties();
        properties.setVerifyCodeExpireMinutes(10);
        when(emailDispatchService.sendByTemplate(
                eq("user@example.com"),
                eq("EMAIL_BIND_CODE"),
                any(),
                eq("VERIFY_CODE"),
                eq("EMAIL_BIND"),
                eq(null)
        )).thenReturn(9L);
        VerifyCodeService service = new VerifyCodeService(
                verifyCodeMapper,
                emailDispatchService,
                emailRateLimiter,
                properties,
                emailSendingAvailabilityService
        );

        service.sendCode("USER@example.com", "email_bind", "127.0.0.1");

        verify(emailRateLimiter).requireVerifyCodeAllowed("user@example.com", "127.0.0.1");
        ArgumentCaptor<VerifyCode> codeCaptor = ArgumentCaptor.forClass(VerifyCode.class);
        verify(verifyCodeMapper).upsert(codeCaptor.capture());
        VerifyCode saved = codeCaptor.getValue();
        assertEquals("user@example.com", saved.getEmail());
        assertEquals("EMAIL_BIND", saved.getScene());
        assertTrue(saved.getCode().matches("\\d{6}"));
        assertFalse(saved.getUsed());
        assertEquals(9L, saved.getEmailRecordId());
        verify(emailSendingAvailabilityService).requireSendingAvailable();
    }

    @Test
    void sendCodeShouldRejectWhenSendingDisabledBeforeRateLimitAndPersistence() {
        EmailProperties properties = new EmailProperties();
        BusinessException disabled = new BusinessException(
                ErrorCode.BAD_REQUEST,
                EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE
        );
        doThrow(disabled).when(emailSendingAvailabilityService).requireSendingAvailable();
        VerifyCodeService service = new VerifyCodeService(
                verifyCodeMapper,
                emailDispatchService,
                emailRateLimiter,
                properties,
                emailSendingAvailabilityService
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.sendCode("USER@example.com", "email_bind", "127.0.0.1"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE, exception.getMessage());
        verify(emailSendingAvailabilityService).requireSendingAvailable();
        verifyNoInteractions(emailRateLimiter, emailDispatchService, verifyCodeMapper);
    }

    @Test
    void checkCodeShouldRejectInvalidCode() {
        EmailProperties properties = new EmailProperties();
        VerifyCodeService service = new VerifyCodeService(
                verifyCodeMapper,
                emailDispatchService,
                emailRateLimiter,
                properties,
                emailSendingAvailabilityService
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.checkCode("user@example.com", "EMAIL_BIND", "abc"));

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals("验证码无效或已过期", exception.getMessage());
    }

    @Test
    void checkCodeShouldMarkCodeUsed() {
        EmailProperties properties = new EmailProperties();
        when(verifyCodeMapper.markUsedByCode("user@example.com", "EMAIL_BIND", "123456")).thenReturn(1);
        VerifyCodeService service = new VerifyCodeService(
                verifyCodeMapper,
                emailDispatchService,
                emailRateLimiter,
                properties,
                emailSendingAvailabilityService
        );

        service.checkCode("user@example.com", "EMAIL_BIND", "123456");

        verify(verifyCodeMapper).markUsedByCode("user@example.com", "EMAIL_BIND", "123456");
    }
}
