package uno.acloud.email.application;

import org.junit.jupiter.api.Test;
import uno.acloud.common.ErrorCode;

import java.util.Optional;
import uno.acloud.email.config.EmailProperties;
import uno.acloud.email.dto.EmailRuntimeStatusVO;
import uno.acloud.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmailSendingAvailabilityServiceTest {

    @Test
    void requireSendingAvailableShouldRejectWhenSendingDisabled() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(false);
        EmailServerConfigService configService = mock(EmailServerConfigService.class);
        EmailSendingAvailabilityService service = new EmailSendingAvailabilityService(properties, configService);

        BusinessException exception = assertThrows(BusinessException.class, service::requireSendingAvailable);

        assertEquals(ErrorCode.BAD_REQUEST, exception.getErrorCode());
        assertEquals(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE, exception.getMessage());
        verifyNoInteractions(configService);
    }

    @Test
    void getRuntimeStatusShouldExposeSendingSwitchAndActiveConfigState() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(false);
        EmailServerConfigService configService = mock(EmailServerConfigService.class);
        EmailSendingAvailabilityService service = new EmailSendingAvailabilityService(properties, configService);

        EmailRuntimeStatusVO disabledStatus = service.getRuntimeStatus();

        assertFalse(disabledStatus.getSendingEnabled());
        assertFalse(disabledStatus.getActiveServerConfigured());
        assertEquals(EmailSendingAvailabilityService.SEND_DISABLED_MESSAGE, disabledStatus.getMessage());

        properties.setEnabled(true);
        when(configService.getCurrentConfig()).thenReturn(Optional.of(new uno.acloud.email.vo.EmailServerConfigVO()));
        EmailRuntimeStatusVO enabledStatus = service.getRuntimeStatus();

        assertTrue(enabledStatus.getSendingEnabled());
        assertTrue(enabledStatus.getActiveServerConfigured());
        assertEquals("邮件发送已开启", enabledStatus.getMessage());
    }
}
